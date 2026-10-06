package io.till.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.till.core.BatchDecision;
import io.till.core.BatchSnapshot;
import io.till.core.Command;
import io.till.core.Decision;
import io.till.core.Ledger;
import io.till.core.LedgerInspector;
import io.till.core.OutboxEntry;
import io.till.core.Reservation;
import io.till.core.ReservationId;
import io.till.core.ReservationState;
import io.till.core.Sku;
import io.till.core.Snapshot;
import io.till.core.StockItem;
import io.till.core.StockShard;
import io.till.core.mem.InMemoryLedger;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Batches answer exactly as one command at a time does (ADR 16), on the ledger in a few maps.
 *
 * <p>{@code JdbcBatchEquivalenceTest} runs the same histories against PostgreSQL.
 */
class BatchEquivalenceTest {

    @ParameterizedTest(name = "seed {0}")
    @ValueSource(longs = {1, 2, 3, 7, 8123, 20260910})
    @DisplayName("batches of random sizes give every command the answer one at a time gives it, and leave every row as it leaves it")
    void batchesAnswerAsOneAtATime(long seed) {
        BatchEquivalence.Report report = BatchEquivalence.check(seed, InMemoryLedger::new);

        assertTrue(report.batchesOfMany() > 0, report.summary());
        assertTrue(report.invariantChecks() > report.batches(), "checked after every batch and every command: " + report.summary());
    }

    @Test
    @DisplayName("over two hundred seeds the histories did everything the equivalence is meant to cover")
    void theHistoriesWereHostile() {
        long keysTwiceInABatch = 0;
        long earlierHoldsFinished = 0;
        long holdsFinishedInTheirOwnBatch = 0;
        long shortfallsOnTheirOwn = 0;
        long deadlinesExceeded = 0;
        long refusedKeyReuse = 0;
        for (long seed = 1; seed <= 200; seed++) {
            BatchEquivalence.Report report = BatchEquivalence.check(seed, InMemoryLedger::new);
            keysTwiceInABatch += report.keysTwiceInABatch();
            earlierHoldsFinished += report.earlierHoldsFinished();
            holdsFinishedInTheirOwnBatch += report.holdsFinishedInTheirOwnBatch();
            shortfallsOnTheirOwn += report.shortfallsOnTheirOwn();
            deadlinesExceeded += report.deadlinesExceeded();
            refusedKeyReuse += report.keysReusedAndRefused();
        }

        // A suite that passes because its histories never did the hard thing proves nothing about it.
        assertTrue(keysTwiceInABatch > 200, "a key sent twice in one batch: " + keysTwiceInABatch);
        assertTrue(earlierHoldsFinished > 200, "holds from an earlier batch committed or released: " + earlierHoldsFinished);
        assertTrue(holdsFinishedInTheirOwnBatch > 50, "holds committed or released in the batch that took them: "
                + holdsFinishedInTheirOwnBatch);
        assertTrue(shortfallsOnTheirOwn > 200, "shortfalls decided on their own, with the expired holds: " + shortfallsOnTheirOwn);
        assertTrue(deadlinesExceeded > 200, "callers already gone: " + deadlinesExceeded);
        assertTrue(refusedKeyReuse > 50, "keys reused for a different request: " + refusedKeyReuse);
    }

    @Test
    @DisplayName("the same seed is the same history and the same report")
    void isDeterministic() {
        assertEquals(BatchEquivalence.check(8123, InMemoryLedger::new), BatchEquivalence.check(8123, InMemoryLedger::new));
    }

    /**
     * Every flaw but {@link Flaw#LOST_UPDATE}, which needs a second writer between a read and a write
     * to lose anything, and a history run one batch at a time has none; {@code JdbcBatchConcurrencyTest}
     * is where batches race.
     */
    static Stream<Arguments> flaws() {
        return Stream.of(
                Arguments.of(Flaw.NO_IDEMPOTENCY),
                Arguments.of(Flaw.PARTIAL_APPLY),
                Arguments.of(Flaw.RESERVED_IGNORED));
    }

    @ParameterizedTest(name = "{0}, in the batches only")
    @MethodSource("flaws")
    @DisplayName("a known mistake in how batches are loaded or written fails the run")
    void aFlawInTheBatchesIsCaught(Flaw flaw) {
        // Commands one at a time go to a sound ledger, batches to a flawed view of the same one: the
        // one-at-a-time run passes, and only the batches can fail it.
        for (long seed = 1; seed <= 5; seed++) {
            long chosen = seed;
            assertThrows(
                    InvariantViolation.class,
                    () -> BatchEquivalence.check(chosen, () -> new BatchesOnly(new InMemoryLedger(), flaw)),
                    flaw + " in the batches survived seed " + chosen);
        }
    }

    @Test
    @DisplayName("a batch whose answers differ from one at a time fails the comparison itself, not only the invariants")
    void aDifferentAnswerIsCaught() {
        // Writes every batch but forgets its records: every row stays consistent, so no invariant can
        // see it, and only the next command with one of those keys answers differently.
        InvariantViolation violation = assertThrows(
                InvariantViolation.class,
                () -> BatchEquivalence.check(8123, () -> new Rewriting(new InMemoryLedger(), decision -> new BatchDecision(
                        decision.outcomes(), decision.stock(), decision.states(), decision.inserts(), decision.events(),
                        List.of()))));

        assertTrue(violation.getMessage().startsWith("Batches answer as one at a time"), violation.getMessage());
    }

    @Test
    @DisplayName("a batch that leaves a row other than one at a time leaves it fails the comparison of the rows")
    void aDifferentRowIsCaught() {
        // Writes every stock row a version further on than one at a time would: every answer the same,
        // every invariant intact, and the rows not.
        InvariantViolation violation = assertThrows(
                InvariantViolation.class,
                () -> BatchEquivalence.check(8123, () -> new Rewriting(new InMemoryLedger(), decision -> new BatchDecision(
                        decision.outcomes(),
                        decision.stock().stream()
                                .map(write -> new BatchDecision.StockWrite(write.sku(), write.shard(), write.onHand(),
                                        write.reserved(), write.expectedVersion(), write.newVersion() + 1))
                                .toList(),
                        decision.states(), decision.inserts(), decision.events(), decision.records()))));

        assertTrue(violation.getMessage().startsWith("Batches leave the rows one at a time leaves"), violation.getMessage());
    }

    /** A ledger whose batch methods go through a flaw and whose single ones do not. */
    private static final class BatchesOnly extends Delegating {
        private final FlawedLedger flawed;

        BatchesOnly(InMemoryLedger sound, Flaw flaw) {
            super(sound);
            this.flawed = new FlawedLedger(sound, flaw);
        }

        @Override
        public BatchSnapshot loadBatch(List<Command> commands) {
            return flawed.loadBatch(commands);
        }

        @Override
        public boolean applyBatch(BatchDecision decision) {
            return flawed.applyBatch(decision);
        }
    }

    /** A ledger that writes something other than what a batch decided. */
    private static final class Rewriting extends Delegating {
        private final UnaryOperator<BatchDecision> rewrite;

        Rewriting(InMemoryLedger sound, UnaryOperator<BatchDecision> rewrite) {
            super(sound);
            this.rewrite = rewrite;
        }

        @Override
        public boolean applyBatch(BatchDecision decision) {
            return super.applyBatch(rewrite.apply(decision));
        }
    }

    /** Everything passed through to an in-memory ledger. */
    private static class Delegating implements Ledger, LedgerInspector {
        private final InMemoryLedger sound;

        Delegating(InMemoryLedger sound) {
            this.sound = sound;
        }

        @Override
        public Snapshot load(Command command, Instant now, int reclaimLimit) {
            return sound.load(command, now, reclaimLimit);
        }

        @Override
        public boolean apply(Decision decision) {
            return sound.apply(decision);
        }

        @Override
        public BatchSnapshot loadBatch(List<Command> commands) {
            return sound.loadBatch(commands);
        }

        @Override
        public boolean applyBatch(BatchDecision decision) {
            return sound.applyBatch(decision);
        }

        @Override
        public Optional<StockItem> stock(Sku sku) {
            return sound.stock(sku);
        }

        @Override
        public Optional<Reservation> reservation(ReservationId id) {
            return sound.reservation(id);
        }

        @Override
        public List<StockItem> listStock(Optional<Sku> after, int limit) {
            return sound.listStock(after, limit);
        }

        @Override
        public List<Reservation> listReservations(Optional<ReservationState> state, int limit) {
            return sound.listReservations(state, limit);
        }

        @Override
        public List<StockItem> allStock() {
            return sound.allStock();
        }

        @Override
        public List<StockShard> allShards() {
            return sound.allShards();
        }

        @Override
        public List<Reservation> allReservations() {
            return sound.allReservations();
        }

        @Override
        public List<OutboxEntry> allEvents() {
            return sound.allEvents();
        }
    }
}
