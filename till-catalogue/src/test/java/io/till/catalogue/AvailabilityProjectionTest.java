package io.till.catalogue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.till.catalogue.AvailabilityProjection.Availability;
import io.till.core.Event;
import io.till.core.IdempotencyKey;
import io.till.core.Line;
import io.till.core.ReservationId;
import io.till.core.Sku;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The read model, against a real PostgreSQL.
 *
 * <p>The assertions that matter are about <b>redelivery</b>. At-least-once is not an unlikely edge
 * here: a publisher that dies between a successful send and the write recording it repeats the send
 * on restart, by design, and the reservation events carry deltas. So "the same event twice leaves
 * the numbers alone" is the property the whole consuming side rests on, and it is checked on every
 * event type rather than on a representative one.
 */
@SpringBootTest
class AvailabilityProjectionTest {

    private static final Instant T0 = Instant.parse("2026-09-18T12:00:00Z");

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    AvailabilityProjection projection;

    @Autowired
    DataSource dataSource;

    @BeforeEach
    void clear() throws SQLException {
        try (var connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("truncate catalogue_availability, catalogue_consumed_event");
        }
    }

    @Test
    @DisplayName("an adjustment sets the level, because it is the one event carrying absolute numbers")
    void adjustmentSetsTheLevel() {
        assertTrue(apply("adjusted:d1", 1, adjusted("d1", "tessera", 10, 10, 0)));

        assertEquals(10, level("tessera").onHand());
        assertEquals(0, level("tessera").reserved());
        assertEquals(10, level("tessera").available());
        assertEquals(T0, level("tessera").updatedAt(), "till's clock, so the age of the number is the age of the news");
    }

    @Test
    @DisplayName("a hold moves units out of available without selling them")
    void aHoldReducesAvailable() {
        apply("adjusted:d1", 1, adjusted("d1", "tessera", 10, 10, 0));

        apply("reserved:r1", 2, reserved("r1", "tessera", 3));

        assertEquals(10, level("tessera").onHand(), "nothing has left the warehouse yet");
        assertEquals(3, level("tessera").reserved());
        assertEquals(7, level("tessera").available());
    }

    @Test
    @DisplayName("paying lowers on-hand and reserved together, so available does not move")
    void payingDoesNotChangeAvailable() {
        apply("adjusted:d1", 1, adjusted("d1", "tessera", 10, 10, 0));
        apply("reserved:r1", 2, reserved("r1", "tessera", 3));

        apply("committed:r1", 3, new Event.StockCommitted(ReservationId.of("r1"), lines("tessera", 3), T0));

        // The units stopped being available when they were held, not when they were paid for. A
        // projection that dropped available again here would double-count every sale.
        assertEquals(7, level("tessera").onHand());
        assertEquals(0, level("tessera").reserved());
        assertEquals(7, level("tessera").available());
    }

    @Test
    @DisplayName("releasing and expiring both give the units back")
    void givingBackRestoresAvailable() {
        apply("adjusted:d1", 1, adjusted("d1", "tessera", 10, 10, 0));
        apply("reserved:r1", 2, reserved("r1", "tessera", 2));
        apply("reserved:r2", 3, reserved("r2", "tessera", 3));

        apply("released:r1", 4, new Event.StockReleased(ReservationId.of("r1"), lines("tessera", 2), T0));
        apply("expired:r2", 5, new Event.StockExpired(ReservationId.of("r2"), lines("tessera", 3), T0));

        assertEquals(10, level("tessera").available(), "both holds are gone and nothing was sold");
        assertEquals(0, level("tessera").reserved());
    }

    @Test
    @DisplayName("the same event delivered twice moves nothing the second time")
    void redeliveryIsANoOp() {
        apply("adjusted:d1", 1, adjusted("d1", "tessera", 10, 10, 0));

        assertTrue(apply("reserved:r1", 2, reserved("r1", "tessera", 4)));
        assertFalse(apply("reserved:r1", 2, reserved("r1", "tessera", 4)), "the inbox recognised it");

        // Without the inbox this would read 2. The events carry deltas, so a duplicate does not
        // merely waste work — it corrupts the number, permanently and silently.
        assertEquals(6, level("tessera").available());
        assertEquals(4, level("tessera").reserved());
    }

    @Test
    @DisplayName("every event type is protected, not just the one somebody remembered")
    void redeliveryIsANoOpForEveryKind() {
        List<Event> all =
                List.of(
                        adjusted("d1", "lantern-run", 20, 20, 0),
                        reserved("r1", "lantern-run", 5),
                        new Event.StockCommitted(ReservationId.of("r1"), lines("lantern-run", 5), T0),
                        reserved("r2", "lantern-run", 4),
                        new Event.StockReleased(ReservationId.of("r2"), lines("lantern-run", 4), T0),
                        reserved("r3", "lantern-run", 2),
                        new Event.StockExpired(ReservationId.of("r3"), lines("lantern-run", 2), T0));

        long sequence = 1;
        for (Event event : all) {
            assertTrue(apply(event.dedupeKey(), sequence++, event));
        }
        Availability once = level("lantern-run");

        // The whole stream again, which is what a consumer group rebalance or a republished batch
        // actually looks like.
        long replay = 100;
        for (Event event : all) {
            assertFalse(apply(event.dedupeKey(), replay++, event), event.dedupeKey() + " was applied twice");
        }

        assertEquals(once, level("lantern-run"), "replaying the entire history changed nothing");
        assertEquals(15, once.onHand(), "20 in, 5 sold");
        assertEquals(15, once.available());
    }

    @Test
    @DisplayName("a later adjustment repairs a projection that has drifted")
    void anAdjustmentRepairsDrift() {
        apply("adjusted:d1", 1, adjusted("d1", "deep-field", 10, 10, 0));
        apply("reserved:r1", 2, reserved("r1", "deep-field", 3));

        // Whatever the cause — a missed event, a bad migration, a restore from a stale backup — the
        // next adjustment carries the true level rather than a delta, so it puts the row right
        // instead of adding to a wrong number.
        apply("adjusted:d2", 3, adjusted("d2", "deep-field", 5, 99, 1));

        assertEquals(99, level("deep-field").onHand());
        assertEquals(1, level("deep-field").reserved());
        assertEquals(98, level("deep-field").available());
    }

    @Test
    @DisplayName("a hold on a SKU nothing has been heard about still projects")
    void anUnknownSkuIsUpserted() {
        // till does not promise this service saw the stock arrive — a consumer joining late, or a
        // topic whose retention dropped the adjustment, both land here.
        apply("reserved:r1", 1, reserved("r1", "night-shift-audio", 2));

        assertEquals(2, level("night-shift-audio").reserved());
        assertEquals(-2, level("night-shift-audio").available(), "honest about what it knows rather than clamped");
    }

    @Test
    @DisplayName("one event moving several SKUs moves all of them")
    void multiLineEventsHitEveryLine() {
        apply("adjusted:d1", 1, adjusted("d1", "tessera", 10, 10, 0));
        apply("adjusted:d2", 2, adjusted("d2", "deep-field", 10, 10, 0));

        apply(
                "reserved:r1",
                3,
                new Event.StockReserved(
                        ReservationId.of("r1"),
                        List.of(Line.of("tessera", 2), Line.of("deep-field", 3)),
                        T0.plus(Duration.ofMinutes(15)),
                        T0));

        assertEquals(8, level("tessera").available());
        assertEquals(7, level("deep-field").available());
    }

    private boolean apply(String dedupeKey, long sequence, Event event) {
        return projection.apply(dedupeKey, sequence, event);
    }

    private Availability level(String sku) {
        return projection.of(sku).orElseThrow(() -> new AssertionError("no availability row for " + sku));
    }

    private static Event.StockAdjusted adjusted(String key, String sku, long delta, long onHand, long reserved) {
        return new Event.StockAdjusted(IdempotencyKey.of(key), Sku.of(sku), delta, onHand, reserved, T0);
    }

    private static Event.StockReserved reserved(String id, String sku, long quantity) {
        return new Event.StockReserved(
                ReservationId.of(id), lines(sku, quantity), T0.plus(Duration.ofMinutes(15)), T0);
    }

    private static List<Line> lines(String sku, long quantity) {
        return List.of(Line.of(sku, quantity));
    }
}
