package io.till.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.till.core.Event;
import io.till.core.IdempotencyKey;
import io.till.core.Line;
import io.till.core.ReservationId;
import io.till.core.Sku;
import io.till.store.catalogue.Availability;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/**
 * The projection, handed the ledger's events: every read model an event moves, and that each moves
 * exactly once however many times the event arrives.
 *
 * <p>Events are built by hand where the point is one event's arithmetic, and come from the real kernel
 * — through {@link EmbeddedLedger#deliver} — where the point is that what the ledger actually writes
 * projects correctly.
 */
class ProjectorTest extends StoreTest {

    private final AtomicLong sequence = new AtomicLong();

    @Autowired
    Availability availability;

    @Nested
    @DisplayName("availability")
    class Levels {

        @Test
        @DisplayName("a hold takes units out of what is available, and leaves on-hand alone")
        void hold() {
            apply(adjusted("tessera", 10, 0));
            apply(reserved("r-1", "tessera", 3));

            assertLevel("tessera", 10, 3, 7);
        }

        @Test
        @DisplayName("a sale takes on-hand and reserved down together, so available does not drop a second time")
        void sale() {
            apply(adjusted("tessera", 10, 0));
            apply(reserved("r-1", "tessera", 3));
            apply(new Event.StockCommitted(ReservationId.of("r-1"), List.of(Line.of("tessera", 3)), T0));

            // The three stopped being available when they were held, not when they were paid for.
            assertLevel("tessera", 7, 0, 7);
        }

        @Test
        @DisplayName("a release and an expiry both give the units back")
        void givenBack() {
            apply(adjusted("tessera", 10, 0));
            apply(reserved("r-1", "tessera", 3));
            apply(reserved("r-2", "tessera", 2));
            apply(new Event.StockReleased(ReservationId.of("r-1"), List.of(Line.of("tessera", 3)), T0));
            apply(new Event.StockExpired(ReservationId.of("r-2"), List.of(Line.of("tessera", 2)), T0));

            assertLevel("tessera", 10, 0, 10);
        }

        @Test
        @DisplayName("a hold that arrives before its stock is still counted, and shows as none to buy, not minus two")
        void holdBeforeStock() {
            // The ledger does not promise this service saw the stock arrive first — a consumer that
            // started late, or a topic whose retention dropped the adjustment.
            apply(reserved("r-1", "canopy", 2));

            Availability.Level early = availability.of("canopy").orElseThrow();
            assertEquals(-2, early.available());
            assertEquals(0, early.buyable());

            apply(adjusted("canopy", 5, 2));
            assertLevel("canopy", 5, 2, 3);
        }

        @Test
        @DisplayName("an adjustment carries levels rather than a change, so it repairs whatever had drifted")
        void adjustmentRepairs() {
            apply(adjusted("canopy", 5, 0));
            jdbc.sql("update store_availability set on_hand = 999, reserved = -4, available = 1003 where sku = 'canopy'")
                    .update();

            apply(adjusted("canopy", 8, 1));

            assertLevel("canopy", 8, 1, 7);
        }

        @Test
        @DisplayName("every level carries the ledger's decision instant, not the moment it was projected")
        void stampedWithTheLedgersClock() {
            clock.advance(Duration.ofHours(3));
            apply(reserved("r-1", "tessera", 1));

            assertEquals(T0, availability.of("tessera").orElseThrow().updatedAt());
        }
    }

    @Nested
    @DisplayName("sales")
    class Sales {

        @Test
        @DisplayName("a sale is counted on the ledger's day, in UTC")
        void utcDay() {
            Instant lastSecondOfTheEighteenth = Instant.parse("2026-09-18T23:59:59Z");
            Instant firstSecondOfTheNineteenth = Instant.parse("2026-09-19T00:00:01Z");
            apply(new Event.StockCommitted(ReservationId.of("r-1"), List.of(Line.of("canopy", 1)), lastSecondOfTheEighteenth));
            apply(new Event.StockCommitted(ReservationId.of("r-2"), List.of(Line.of("canopy", 2)), firstSecondOfTheNineteenth));
            apply(new Event.StockCommitted(ReservationId.of("r-3"), List.of(Line.of("canopy", 4)), firstSecondOfTheNineteenth));

            assertEquals(Map.of(LocalDate.parse("2026-09-18"), 1L, LocalDate.parse("2026-09-19"), 6L), salesOf("canopy"));
        }

        @Test
        @DisplayName("holds, releases and expiries are not sales")
        void onlyCommitsCount() {
            apply(reserved("r-1", "canopy", 2));
            apply(new Event.StockReleased(ReservationId.of("r-1"), List.of(Line.of("canopy", 2)), T0));
            apply(reserved("r-2", "canopy", 1));
            apply(new Event.StockExpired(ReservationId.of("r-2"), List.of(Line.of("canopy", 1)), T0));

            assertEquals(Map.of(), salesOf("canopy"));
        }
    }

    @Nested
    @DisplayName("orders")
    class Orders {

        @Test
        @DisplayName("a hold released at the ledger cancels the order it belongs to")
        void releaseCancels() throws Exception {
            ledger.stock("hexfall", 3);
            UUID order = place("alice", "hexfall", 1);

            ledger.release(IdempotencyKey.of("released-elsewhere"), reservationOf(order));
            ledger.deliver(projector);

            assertEquals("CANCELLED", statusOf(order));
        }

        @Test
        @DisplayName("an expiry that arrives after the order was paid cannot walk it backwards")
        void lateExpiry() throws Exception {
            ledger.stock("hexfall", 3);
            UUID order = place("alice", "hexfall", 1);
            mvc.perform(post("/api/orders/" + order + "/pay").with(customer("alice")).with(csrfToken()).header("Idempotency-Key", key()))
                    .andExpect(status().isOk());

            // Delivered late and out of order — which at-least-once delivery is allowed to do.
            boolean applied = apply(new Event.StockExpired(reservationOf(order), List.of(Line.of("hexfall", 1)), T0));

            assertTrue(applied, "the event itself is new, and is claimed");
            assertEquals("PAID", statusOf(order), "but the order it names has moved on, and stays moved");
        }
    }

    @Nested
    @DisplayName("redelivery")
    class Redelivery {

        @Test
        @DisplayName("an event delivered twice is applied once")
        void twice() {
            Event hold = reserved("r-1", "tessera", 3);
            apply(adjusted("tessera", 10, 0));

            assertTrue(projector.apply("dedupe-1", 1, hold));
            assertFalse(projector.apply("dedupe-1", 1, hold));

            assertLevel("tessera", 10, 3, 7);
        }

        @Test
        @DisplayName("replaying the whole history moves nothing — not a level, not a sale, not one order")
        void wholeHistory() throws Exception {
            ledger.stock("sunless-orbit", 6);
            ledger.stock("ninefold", 4);
            UUID paid = place("alice", "sunless-orbit", 2);
            UUID cancelled = place("bob", "ninefold", 1);
            UUID expired = place("carol", "sunless-orbit", 1);
            mvc.perform(post("/api/orders/" + paid + "/pay").with(customer("alice")).with(csrfToken()).header("Idempotency-Key", key()));
            mvc.perform(post("/api/orders/" + cancelled + "/cancel").with(customer("bob")).with(csrfToken()).header("Idempotency-Key", key()));
            clock.advance(Duration.ofMinutes(20));
            ledger.sweep();
            ledger.deliver(projector);
            String before = everythingProjected();
            assertEquals(List.of("PAID", "CANCELLED", "EXPIRED"), List.of(statusOf(paid), statusOf(cancelled), statusOf(expired)));

            // A consumer-group rebalance, a republished batch, a consumer restarted from offset zero.
            ledger.redeliverEverything(projector);

            assertEquals(before, everythingProjected());
        }
    }

    // --- helpers -----------------------------------------------------------------------------------

    private boolean apply(Event event) {
        long next = sequence.incrementAndGet();
        return projector.apply("test-" + next, next, event);
    }

    private static Event adjusted(String sku, long onHand, long reserved) {
        return new Event.StockAdjusted(IdempotencyKey.of("adjust-" + UUID.randomUUID()), Sku.of(sku), 0, onHand, reserved, T0);
    }

    private static Event reserved(String id, String sku, long units) {
        return new Event.StockReserved(ReservationId.of(id), List.of(Line.of(sku, units)), T0.plus(Duration.ofMinutes(15)), T0);
    }

    private void assertLevel(String sku, long onHand, long reserved, long available) {
        Availability.Level level = availability.of(sku).orElseThrow();
        assertEquals(List.of(onHand, reserved, available), List.of(level.onHand(), level.reserved(), level.available()),
                "on hand, reserved, available");
    }

    private Map<LocalDate, Long> salesOf(String sku) {
        return jdbc.sql("select day, units from store_sales_daily where sku = ?")
                .param(sku)
                .query((rs, row) -> Map.entry(rs.getObject("day", LocalDate.class), rs.getLong("units")))
                .list()
                .stream()
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private UUID place(String customer, String sku, int quantity) throws Exception {
        String body = mvc.perform(post("/api/orders")
                        .with(customer(customer))
                        .with(csrfToken())
                        .header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lines\":[{\"sku\":\"" + sku + "\",\"quantity\":" + quantity + "}]}"))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.id"));
    }

    private ReservationId reservationOf(UUID order) {
        return ReservationId.of(jdbc.sql("select reservation_id from store_order where id = ?")
                .param(order)
                .query(String.class)
                .single());
    }

    private String statusOf(UUID order) {
        return jdbc.sql("select status from store_order where id = ?").param(order).query(String.class).single();
    }

    /** Every row the projection writes, as text, in a fixed order — so two snapshots compare whole. */
    private String everythingProjected() {
        List<String> rows = new java.util.ArrayList<>();
        jdbc.sql("select sku, on_hand, reserved, available, updated_at from store_availability order by sku")
                .query((rs, row) -> rows.add("availability " + rs.getString(1) + " " + rs.getLong(2) + " " + rs.getLong(3)
                        + " " + rs.getLong(4) + " " + rs.getObject(5, OffsetDateTime.class).toInstant()))
                .list();
        jdbc.sql("select sku, day, units from store_sales_daily order by sku, day")
                .query((rs, row) -> rows.add("sales " + rs.getString(1) + " " + rs.getObject(2, LocalDate.class) + " " + rs.getLong(3)))
                .list();
        jdbc.sql("select id, status, closed_at from store_order order by id")
                .query((rs, row) -> rows.add("order " + rs.getObject(1) + " " + rs.getString(2) + " " + rs.getObject(3, OffsetDateTime.class)))
                .list();
        return String.join("\n", rows);
    }
}
