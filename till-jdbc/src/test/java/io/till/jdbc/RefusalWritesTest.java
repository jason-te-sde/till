package io.till.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import io.till.core.Command;
import io.till.core.Decision;
import io.till.core.Event;
import io.till.core.IdempotencyKey;
import io.till.core.Kernel;
import io.till.core.Line;
import io.till.core.Mutation;
import io.till.core.Outcome;
import io.till.core.RejectionCode;
import io.till.core.ReservationId;
import io.till.core.ReservationState;
import io.till.core.Sku;
import io.till.core.Snapshot;
import io.till.core.StockShard;
import io.till.core.Till;
import io.till.core.mem.InMemoryLedger;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What the kernel writes when it refuses a command, which is what lets {@code JdbcLedger} commit a
 * refusal without waiting for the disk (ADR 15): the expired holds the command wrote off on its way,
 * its own hold's expiry when that is why it was refused, and its record. A crash that loses those
 * leaves holds that expire again and a command a retry decides afresh. A refusal that moved stock for
 * good would need the disk, and this is where it would show.
 *
 * <p>No database: the decisions are the kernel's, on snapshots the in-memory ledger loads.
 */
class RefusalWritesTest {

    private static final Instant T0 = Instant.parse("2026-09-10T12:00:00Z");
    private static final Instant LATER = T0.plus(Duration.ofHours(1));
    private static final Duration TTL = Duration.ofMinutes(15);

    @Test
    @DisplayName("every kind of refusal writes nothing but expired holds and its record")
    void aRefusalWritesOnlyExpiries() {
        InMemoryLedger ledger = new InMemoryLedger();
        Till till = Till.builder(ledger).clock(Clock.fixed(T0, ZoneOffset.UTC)).build();
        till.adjust(key("d-widget"), sku("widget"), 10);
        till.adjust(key("d-gadget"), sku("gadget"), 5);
        // Expired by LATER and still stored as held, so a command on widget writes it off on its way.
        till.reserve(key("k-stale"), rid("stale"), List.of(Line.of("widget", 3)), Duration.ofMinutes(1));
        till.reserve(key("k-held"), rid("held"), List.of(Line.of("gadget", 1)), Duration.ofHours(2));
        till.reserve(key("k-paid"), rid("paid"), List.of(Line.of("widget", 1)), Duration.ofHours(2));
        till.commit(key("p-paid"), rid("paid"));
        till.reserve(key("k-gone"), rid("gone"), List.of(Line.of("widget", 1)), Duration.ofHours(2));
        till.release(key("c-gone"), rid("gone"));

        // Each is decided against the same state, so each one that names widget finds the stale hold.
        List<Command> refused = List.of(
                new Command.Reserve(key("r1"), rid("held"), List.of(Line.of("widget", 1)), TTL),
                new Command.Reserve(key("r2"), rid("new-2"), List.of(Line.of("ghost", 1)), TTL),
                new Command.Reserve(key("r3"), rid("new-3"), List.of(Line.of("widget", 100)), TTL),
                new Command.Commit(key("r4"), rid("nowhere")),
                new Command.Commit(key("r5"), rid("paid")),
                new Command.Commit(key("r6"), rid("gone")),
                new Command.Commit(key("r7"), rid("stale")),
                new Command.Release(key("r8"), rid("paid")),
                new Command.Release(key("r9"), rid("nowhere")),
                new Command.Adjust(key("r10"), sku("widget"), -100),
                new Command.Adjust(key("r11"), sku("ghost"), -1),
                new Command.Shard(key("r12"), sku("ghost"), 2),
                new Command.Reserve(key("k-held"), rid("new-13"), List.of(Line.of("widget", 2)), TTL));

        Set<RejectionCode> codes = EnumSet.noneOf(RejectionCode.class);
        int expiries = 0;
        for (Command command : refused) {
            Snapshot snapshot = ledger.load(command, LATER, 32);
            Decision decision = Kernel.decide(snapshot, command, LATER);
            Outcome.Rejected rejected = assertInstanceOf(Outcome.Rejected.class, decision.outcome(), command.toString());
            codes.add(rejected.code());

            for (Mutation mutation : decision.mutations()) {
                switch (mutation) {
                    case Mutation.SetReservationState set -> {
                        assertEquals(ReservationState.EXPIRED, set.state(), rejected.code() + " moved a hold: " + set);
                        expiries++;
                    }
                    case Mutation.PutStock put -> {
                        assertFalse(put.isInsert(), rejected.code() + " created stock: " + put);
                        StockShard before = snapshot.shards(put.sku()).get(put.shard());
                        assertEquals(before.onHand(), put.onHand(), rejected.code() + " moved on-hand: " + put);
                        assertTrue(put.reserved() <= before.reserved(), rejected.code() + " held more: " + put);
                    }
                    case Mutation.InsertReservation insert -> fail(rejected.code() + " took a hold: " + insert);
                }
            }
            for (Event event : decision.events()) {
                assertInstanceOf(Event.StockExpired.class, event, rejected.code() + " announced " + event);
            }
        }

        assertEquals(EnumSet.allOf(RejectionCode.class), codes, "a refusal of every kind, so that a new kind is added here");
        assertTrue(expiries > 0, "and some of them wrote an expired hold off on the way");
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
