package io.till.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.till.core.Command;
import io.till.core.Decision;
import io.till.core.IdempotencyKey;
import io.till.core.Kernel;
import io.till.core.Line;
import io.till.core.Outcome;
import io.till.core.OutboxEntry;
import io.till.core.Reservation;
import io.till.core.ReservationId;
import io.till.core.ReservationState;
import io.till.core.Sku;
import io.till.core.Snapshot;
import io.till.core.StockItem;
import io.till.core.Till;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

// Suites that share the one database run one at a time. Everything else in the build still runs in
// parallel; these truncate the tables they are about, a truncate locks the whole table, and two of
// them at once is a deadlock rather than a race.
@ResourceLock("till-database")
@ExtendWith(RequiresDatabase.class)
class JdbcLedgerTest {

    private static final Instant T0 = Instant.parse("2026-09-10T12:00:00Z");
    private static final Duration TTL = Duration.ofMinutes(15);

    private DataSource dataSource;
    private JdbcLedger ledger;
    private Till till;

    /** The same till; named so the listing tests read like the server's own privilege split. */
    private Till admin() {
        return till;
    }

    @BeforeEach
    void setUp() {
        dataSource = PostgresFixture.dataSource();
        PostgresFixture.reset();
        ledger = new JdbcLedger(dataSource);
        till = Till.builder(ledger).clock(Clock.fixed(T0, ZoneOffset.UTC)).build();
    }

    @Test
    @DisplayName("the whole path, against a real database")
    void theHappyPath() {
        till.adjust(key("delivery-41"), sku("widget"), 100);

        Outcome reserved = till.reserve(key("checkout-1"), rid("r1"), List.of(Line.of("widget", 2)), TTL);
        assertInstanceOf(Outcome.Reserved.class, reserved);
        assertEquals(98, ledger.allStock().get(0).available());

        till.commit(key("pay-1"), rid("r1"));

        StockItem after = ledger.allStock().get(0);
        assertEquals(98, after.onHand());
        assertEquals(0, after.reserved());
        assertEquals(ReservationState.COMMITTED, ledger.allReservations().get(0).state());
    }

    @Test
    @DisplayName("an instant survives the round trip through timestamptz exactly")
    void instantsRoundTrip() {
        till.adjust(key("d1"), sku("widget"), 10);
        Outcome outcome = till.reserve(key("c1"), rid("r1"), List.of(Line.of("widget", 1)), TTL);

        Instant promised = ((Outcome.Reserved) outcome).expiresAt();
        assertEquals(promised, ledger.allReservations().get(0).expiresAt());
        assertEquals(T0.plus(TTL), promised);
    }

    @Test
    @DisplayName("a decision made against a version that has moved is refused, and writes nothing")
    void refusesAStaleVersion() {
        till.adjust(key("d1"), sku("widget"), 10);
        Command command = new Command.Reserve(key("k1"), rid("r1"), List.of(Line.of("widget", 1)), TTL);
        Decision stale = Kernel.decide(ledger.load(command, T0, 0), command, T0);

        till.adjust(key("d2"), sku("widget"), 5);

        assertFalse(ledger.apply(stale));
        assertTrue(ledger.allReservations().isEmpty(), "the reservation must not survive the rollback");
        assertEquals(15, ledger.allStock().get(0).onHand());
        assertEquals(
                List.of("adjusted:d1", "adjusted:d2"),
                ledger.allEvents().stream().map(OutboxEntry::dedupeKey).toList(),
                "and neither must its outbox row: only the two adjustments that did happen are there");
    }

    @Test
    @DisplayName("two servers claiming one idempotency key: one writes, the other is told to reload")
    void refusesADuplicateKey() {
        till.adjust(key("d1"), sku("widget"), 10);
        Command command = new Command.Reserve(key("same"), rid("r1"), List.of(Line.of("widget", 1)), TTL);
        Snapshot shared = ledger.load(command, T0, 0);

        Decision first = Kernel.decide(shared, command, T0);
        Command other = new Command.Reserve(key("same"), rid("r2"), List.of(Line.of("widget", 1)), TTL);
        Decision second = Kernel.decide(shared, other, T0);

        assertTrue(ledger.apply(first));
        assertFalse(ledger.apply(second));
        assertEquals(List.of(rid("r1")), ledger.allReservations().stream().map(Reservation::id).toList());
    }

    @Test
    @DisplayName("a commit loads the SKUs the caller never sent")
    void commitScopesToTheReservation() {
        till.adjust(key("d1"), sku("widget"), 10);
        till.adjust(key("d2"), sku("gadget"), 10);
        till.reserve(key("k1"), rid("r1"), List.of(Line.of("widget", 1), Line.of("gadget", 2)), TTL);

        Snapshot snapshot = ledger.load(new Command.Commit(key("k2"), rid("r1")), T0, 32);

        assertEquals(2, snapshot.stock().size());
        assertEquals(2, snapshot.reservation().orElseThrow().lines().size());
    }

    @Test
    @DisplayName("a command reclaims only the expired holds standing in its way")
    void reclaimIsScopedByTheIndexedJoin() {
        till.adjust(key("d1"), sku("widget"), 10);
        till.adjust(key("d2"), sku("gadget"), 10);
        till.reserve(key("k1"), rid("stale-widget"), List.of(Line.of("widget", 10)), Duration.ofMinutes(1));
        till.reserve(key("k2"), rid("stale-gadget"), List.of(Line.of("gadget", 10)), Duration.ofMinutes(1));

        Snapshot snapshot =
                ledger.load(
                        new Command.Reserve(key("k3"), rid("r3"), List.of(Line.of("widget", 1)), TTL),
                        T0.plus(Duration.ofHours(1)),
                        32);

        assertEquals(
                List.of(rid("stale-widget")), snapshot.reclaimable().stream().map(Reservation::id).toList());
    }

    @Test
    @DisplayName("expired stock comes back without a sweep having run")
    void reclaimsOnDemand() {
        Till later = Till.builder(ledger).clock(Clock.fixed(T0.plus(Duration.ofHours(1)), ZoneOffset.UTC)).build();
        till.adjust(key("d1"), sku("widget"), 10);
        till.reserve(key("k1"), rid("r1"), List.of(Line.of("widget", 10)), Duration.ofMinutes(1));

        Outcome outcome = later.reserve(key("k2"), rid("r2"), List.of(Line.of("widget", 10)), TTL);

        assertInstanceOf(Outcome.Reserved.class, outcome);
        assertEquals(
                ReservationState.EXPIRED,
                ledger.allReservations().stream().filter(r -> r.id().equals(rid("r1"))).findFirst().orElseThrow().state());
    }

    @Test
    @DisplayName("the outbox keeps every event in order and remembers what was published")
    void outbox() {
        till.adjust(key("d1"), sku("widget"), 10);
        till.reserve(key("k1"), rid("r1"), List.of(Line.of("widget", 1)), TTL);
        till.commit(key("k2"), rid("r1"));

        List<OutboxEntry> pending = ledger.unpublished(10);
        assertEquals(
                List.of("adjusted:d1", "reserved:r1", "committed:r1"),
                pending.stream().map(OutboxEntry::dedupeKey).toList());

        ledger.markPublished(pending.stream().map(OutboxEntry::sequence).limit(2).toList());
        assertEquals(List.of("committed:r1"), ledger.unpublished(10).stream().map(OutboxEntry::dedupeKey).toList());
    }

    @Test
    @DisplayName("the database refuses an impossible level even if the application asks for one")
    void theCheckConstraintIsReal() throws SQLException {
        till.adjust(key("d1"), sku("widget"), 10);

        try (var connection = dataSource.getConnection();
                var statement = connection.createStatement()) {
            SQLException thrown =
                    assertThrows(
                            SQLException.class,
                            () -> statement.execute("update till_stock set reserved = 11 where sku = 'widget'"));
            assertEquals("23514", thrown.getSQLState(), thrown.getMessage());
        }
    }

    @Test
    @DisplayName("stock pages by SKU, and the page is keyset-based so it cannot skip a row")
    void listsStockByPage() {
        for (String sku : List.of("aaa", "bbb", "ccc", "ddd")) {
            admin().adjust(key("d-" + sku), sku(sku), 5);
        }

        List<StockItem> first = ledger.listStock(Optional.empty(), 2);
        assertEquals(List.of(sku("aaa"), sku("bbb")), first.stream().map(StockItem::sku).toList());

        List<StockItem> second = ledger.listStock(Optional.of(first.get(1).sku()), 2);
        assertEquals(List.of(sku("ccc"), sku("ddd")), second.stream().map(StockItem::sku).toList());

        assertTrue(ledger.listStock(Optional.of(sku("ddd")), 2).isEmpty());
    }

    @Test
    @DisplayName("reservations list newest first, optionally by state")
    void listsReservationsNewestFirst() {
        admin().adjust(key("d1"), sku("widget"), 100);
        // A clock that moves, so "newest first" is a claim about creation order rather than about
        // whatever order the rows happen to come back in.
        for (int i = 1; i <= 3; i++) {
            Till at = Till.builder(ledger).clock(Clock.fixed(T0.plusSeconds(i), ZoneOffset.UTC)).build();
            at.reserve(key("c" + i), rid("r" + i), List.of(Line.of("widget", 1)), TTL);
        }
        till.commit(key("pay-2"), rid("r2"));

        assertEquals(
                List.of(rid("r3"), rid("r2"), rid("r1")),
                ledger.listReservations(Optional.empty(), 10).stream().map(Reservation::id).toList());
        assertEquals(
                List.of(rid("r3"), rid("r1")),
                ledger.listReservations(Optional.of(ReservationState.HELD), 10).stream()
                        .map(Reservation::id)
                        .toList());
        assertEquals(
                List.of(rid("r2")),
                ledger.listReservations(Optional.of(ReservationState.COMMITTED), 10).stream()
                        .map(Reservation::id)
                        .toList());
    }

    @Test
    @DisplayName("a listed reservation comes back with its lines, in one extra query rather than n")
    void listedReservationsCarryTheirLines() {
        admin().adjust(key("d1"), sku("widget"), 100);
        admin().adjust(key("d2"), sku("gadget"), 100);
        till.reserve(key("c1"), rid("r1"), List.of(Line.of("widget", 2), Line.of("gadget", 3)), TTL);

        Reservation listed = ledger.listReservations(Optional.empty(), 10).get(0);

        assertEquals(List.of(Line.of("gadget", 3), Line.of("widget", 2)), listed.lines());
    }

    @Test
    @DisplayName("a limit beyond the maximum is clamped, and a limit of zero is refused")
    void pageSizeIsBounded() {
        admin().adjust(key("d1"), sku("widget"), 1);

        assertEquals(1, ledger.listStock(Optional.empty(), 10_000).size(), "clamped, not refused");
        assertThrows(IllegalArgumentException.class, () -> ledger.listStock(Optional.empty(), 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> ledger.listReservations(Optional.empty(), -1));
    }

    @Test
    @DisplayName("an empty ledger answers rather than failing")
    void emptyLedger() {
        assertTrue(ledger.listStock(Optional.empty(), 10).isEmpty());
        assertTrue(ledger.listReservations(Optional.empty(), 10).isEmpty());
        assertTrue(ledger.allStock().isEmpty());
        assertTrue(ledger.allReservations().isEmpty());
        assertTrue(ledger.allEvents().isEmpty());
        assertTrue(ledger.unpublished(10).isEmpty());
        assertEquals(0, till.sweep(10));

        Outcome outcome = till.reserve(key("k1"), rid("r1"), List.of(Line.of("ghost", 1)), TTL);
        assertEquals(io.till.core.RejectionCode.UNKNOWN_SKU, ((Outcome.Rejected) outcome).code());
    }

    private static Sku sku(String value) {
        return Sku.of(value);
    }

    private static IdempotencyKey key(String value) {
        return IdempotencyKey.of(value);
    }

    private static ReservationId rid(String value) {
        return ReservationId.of(value);
    }
}
