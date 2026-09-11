package io.till.core;

import static io.till.core.Fixtures.T0;
import static io.till.core.Fixtures.TTL;
import static io.till.core.Fixtures.key;
import static io.till.core.Fixtures.line;
import static io.till.core.Fixtures.rid;
import static io.till.core.Fixtures.sku;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.till.core.mem.InMemoryLedger;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class InMemoryLedgerTest {

    private final InMemoryLedger ledger = new InMemoryLedger();
    private final Till till = Till.builder(ledger).clock(Clock.fixed(T0, ZoneOffset.UTC)).build();

    @Test
    @DisplayName("a reserve loads exactly the SKUs it names")
    void reserveScopesToItsLines() {
        till.adjust(key("d1"), sku("widget"), 10);
        till.adjust(key("d2"), sku("gadget"), 10);

        Snapshot snapshot =
                ledger.load(
                        new Command.Reserve(key("k"), rid("r"), List.of(line("widget", 1)), TTL),
                        T0,
                        Till.DEFAULT_RECLAIM_LIMIT);

        assertEquals(List.of(sku("widget")), List.copyOf(snapshot.stock().keySet()));
    }

    @Test
    @DisplayName("a commit takes its scope from the reservation, which the caller did not send")
    void commitScopesToTheReservation() {
        till.adjust(key("d1"), sku("widget"), 10);
        till.adjust(key("d2"), sku("gadget"), 10);
        till.reserve(key("k1"), rid("r1"), List.of(line("widget", 1), line("gadget", 2)), TTL);

        Snapshot snapshot =
                ledger.load(new Command.Commit(key("k2"), rid("r1")), T0, Till.DEFAULT_RECLAIM_LIMIT);

        assertEquals(2, snapshot.stock().size());
        assertTrue(snapshot.reservation().isPresent());
    }

    @Test
    @DisplayName("a SKU with no row is loaded as absent, not left out")
    void absentSkusArePresentInTheSnapshot() {
        Snapshot snapshot =
                ledger.load(
                        new Command.Reserve(key("k"), rid("r"), List.of(line("ghost", 1)), TTL),
                        T0,
                        Till.DEFAULT_RECLAIM_LIMIT);

        assertEquals(Optional.of(StockItem.empty(sku("ghost"))), Optional.ofNullable(snapshot.stock().get(sku("ghost"))));
        assertFalse(snapshot.require(sku("ghost")).exists());
    }

    @Test
    @DisplayName("a decision made against a version that has moved is refused")
    void refusesAStaleVersion() {
        till.adjust(key("d1"), sku("widget"), 10);
        Command command = new Command.Reserve(key("k1"), rid("r1"), List.of(line("widget", 1)), TTL);
        Decision stale = Kernel.decide(ledger.load(command, T0, 0), command, T0);

        // Somebody else got there first.
        till.adjust(key("d2"), sku("widget"), 5);

        assertFalse(ledger.apply(stale));
        assertEquals(1, ledger.conflictCount());
        assertTrue(ledger.reservation(rid("r1")).isEmpty(), "and none of the decision was written");
    }

    @Test
    @DisplayName("two decisions under one key: the second is refused by the unique constraint")
    void refusesADuplicateKey() {
        till.adjust(key("d1"), sku("widget"), 10);
        Command command = new Command.Reserve(key("same"), rid("r1"), List.of(line("widget", 1)), TTL);
        Snapshot snapshot = ledger.load(command, T0, 0);

        // Both callers loaded before either applied, which is what two servers racing looks like.
        Decision first = Kernel.decide(snapshot, command, T0);
        Command other = new Command.Reserve(key("same"), rid("r2"), List.of(line("widget", 1)), TTL);
        Decision second = Kernel.decide(snapshot, other, T0);

        assertTrue(ledger.apply(first));
        assertFalse(ledger.apply(second), "the key was claimed, so the loser reloads and replays");
        assertTrue(ledger.reservation(rid("r2")).isEmpty());
    }

    @Test
    @DisplayName("the outbox keeps every event, in order, and remembers what was published")
    void outboxOrdering() {
        till.adjust(key("d1"), sku("widget"), 10);
        till.reserve(key("k1"), rid("r1"), List.of(line("widget", 1)), TTL);
        till.commit(key("k2"), rid("r1"));

        List<OutboxEntry> pending = ledger.unpublished(100);
        assertEquals(3, pending.size());
        assertEquals(List.of(1L, 2L, 3L), pending.stream().map(OutboxEntry::sequence).toList());
        assertEquals(
                List.of("adjusted:d1", "reserved:r1", "committed:r1"),
                pending.stream().map(OutboxEntry::dedupeKey).toList());

        ledger.markPublished(List.of(1L, 2L), T0);
        assertEquals(List.of(3L), ledger.unpublished(100).stream().map(OutboxEntry::sequence).toList());
    }

    @Test
    @DisplayName("a command writes off only the expired holds standing in its way")
    void reclaimIsScopedToTheCommand() {
        till.adjust(key("d1"), sku("widget"), 10);
        till.adjust(key("d2"), sku("gadget"), 10);
        till.reserve(key("k1"), rid("stale-widget"), List.of(line("widget", 10)), Duration.ofMinutes(1));
        till.reserve(key("k2"), rid("stale-gadget"), List.of(line("gadget", 10)), Duration.ofMinutes(1));

        Snapshot snapshot =
                ledger.load(
                        new Command.Reserve(key("k3"), rid("r3"), List.of(line("widget", 1)), TTL),
                        T0.plus(Duration.ofHours(1)),
                        Till.DEFAULT_RECLAIM_LIMIT);

        // Touching the gadget hold would turn an unrelated caller's commit into a conflict.
        assertEquals(
                List.of(rid("stale-widget")), snapshot.reclaimable().stream().map(Reservation::id).toList());
    }

    @Test
    @DisplayName("a sweep is the one command that looks at everything")
    void sweepIsNotScoped() {
        till.adjust(key("d1"), sku("widget"), 10);
        till.adjust(key("d2"), sku("gadget"), 10);
        till.reserve(key("k1"), rid("r1"), List.of(line("widget", 10)), Duration.ofMinutes(1));
        till.reserve(key("k2"), rid("r2"), List.of(line("gadget", 10)), Duration.ofMinutes(1));

        Snapshot snapshot = ledger.load(new Command.Sweep(100), T0.plus(Duration.ofHours(1)), 100);

        assertEquals(2, snapshot.reclaimable().size());
        assertEquals(2, snapshot.stock().size(), "and it loaded the stock rows those holds need");
    }

    @Test
    @DisplayName("the listings page, filter and clamp")
    void listings() {
        for (String sku : List.of("aaa", "bbb", "ccc")) {
            till.adjust(key("d-" + sku), sku(sku), 5);
        }
        till.reserve(key("k1"), rid("r1"), List.of(line("aaa", 1)), TTL);
        till.release(key("k2"), rid("r1"));
        till.reserve(key("k3"), rid("r2"), List.of(line("bbb", 1)), TTL);

        assertEquals(
                List.of(sku("aaa"), sku("bbb")),
                ledger.listStock(Optional.empty(), 2).stream().map(StockItem::sku).toList());
        assertEquals(
                List.of(sku("ccc")),
                ledger.listStock(Optional.of(sku("bbb")), 2).stream().map(StockItem::sku).toList());
        assertEquals(
                List.of(rid("r2")),
                ledger.listReservations(Optional.of(ReservationState.HELD), 10).stream()
                        .map(Reservation::id)
                        .toList());
        assertEquals(3, ledger.listStock(Optional.empty(), 10_000).size(), "clamped, not refused");
    }

    @Test
    @DisplayName("a reclaim limit of zero turns reclaiming off")
    void reclaimCanBeTurnedOff() {
        till.adjust(key("d1"), sku("widget"), 10);
        till.reserve(key("k1"), rid("r1"), List.of(line("widget", 10)), Duration.ofMinutes(1));

        Snapshot snapshot =
                ledger.load(
                        new Command.Reserve(key("k2"), rid("r2"), List.of(line("widget", 1)), TTL),
                        T0.plus(Duration.ofHours(1)),
                        0);

        assertTrue(snapshot.reclaimable().isEmpty());
    }

    @Test
    @DisplayName("retention keeps what it must and deletes what it may")
    void retention() {
        till.adjust(key("d1"), sku("widget"), 10);
        till.reserve(key("k1"), rid("r1"), List.of(line("widget", 1)), TTL);
        till.release(key("k2"), rid("r1"));
        Instant later = T0.plus(Duration.ofDays(400));

        // An unpublished event is a change nothing downstream has heard about. No window makes
        // deleting one acceptable, so the cutoff is ignored rather than applied.
        assertEquals(0, ledger.pruneOutbox(later, 100));
        assertEquals(3, ledger.allEvents().size());

        ledger.markPublished(List.of(1L, 2L), T0);
        assertEquals(2, ledger.pruneOutbox(later, 100));
        assertEquals(1, ledger.allEvents().size(), "the third was never published");
    }

    @Test
    @DisplayName("an idempotency key is kept while the event named after it is still queued")
    void retentionKeepsAKeyItsEventNeeds() {
        till.adjust(key("d1"), sku("widget"), 10);
        Instant later = T0.plus(Duration.ofDays(400));

        // An adjustment's event is "adjusted:d1", so that name is unique only while the record
        // exists. Forgetting the key here would let a re-execution of the same command collide with
        // its own leftover event, and the caller would be told 503 for good. This is the in-memory
        // half of a rule the JDBC ledger enforces in SQL; a caller embedding till with no database
        // gets the same guarantee or none.
        assertEquals(0, ledger.forgetIdempotency(later, 100));

        ledger.markPublished(List.of(1L), T0);
        ledger.pruneOutbox(later, 100);

        assertEquals(1, ledger.forgetIdempotency(later, 100), "now the event has gone, the key may");
    }

    @Test
    @DisplayName("retention never deletes a held reservation, whatever it is asked")
    void retentionNeverDeletesAHeldReservation() {
        till.adjust(key("d1"), sku("widget"), 10);
        till.reserve(key("k1"), rid("r1"), List.of(line("widget", 4)), TTL);

        // Its four units are counted in reserved. Deleting the row without lowering that counter
        // leaks the stock permanently, so nothing here can be asked to do it.
        assertEquals(0, ledger.pruneReservations(T0.plus(Duration.ofDays(400)), 100));
        assertEquals(4, ledger.stock(sku("widget")).orElseThrow().reserved());
    }

    @Test
    @DisplayName("a batch size below one is refused rather than treated as no limit")
    void retentionValidatesItsBatchSize() {
        assertThrows(IllegalArgumentException.class, () -> ledger.pruneOutbox(T0, 0));
        assertThrows(IllegalArgumentException.class, () -> ledger.forgetIdempotency(T0, -1));
        assertThrows(IllegalArgumentException.class, () -> ledger.pruneReservations(T0, 0));
    }
}
