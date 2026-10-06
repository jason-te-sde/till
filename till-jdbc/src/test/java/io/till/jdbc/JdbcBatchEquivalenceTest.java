package io.till.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.till.core.mem.InMemoryLedger;
import io.till.testkit.BatchEquivalence;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceLock;

/**
 * Batches answer exactly as one command at a time does, on PostgreSQL (ADR 16).
 *
 * <p>The histories of {@code BatchEquivalenceTest} in {@code till-testkit}, each run one command at a
 * time and then in batches of random sizes, every answer and every row compared, versions and outbox
 * sequence numbers included. And the same history on the ledger in a few maps must do exactly what it
 * does here, which is the differential test's promise extended to batches.
 *
 * <p>Fewer seeds than in memory: every command here is a few statements, and every check after a
 * batch reads every table.
 */
// Suites that share the one database run one at a time. Everything else in the build still runs in
// parallel; these truncate the tables they are about, a truncate locks the whole table, and two of
// them at once is a deadlock rather than a race.
@ResourceLock("till-database")
@ExtendWith(RequiresDatabase.class)
class JdbcBatchEquivalenceTest {

    @Test
    @DisplayName("on PostgreSQL, batches of random sizes give every command the answer one at a time gives it, and leave every row as it does")
    void batchesAnswerAsOneAtATime() {
        long keysTwiceInABatch = 0;
        long earlierHoldsFinished = 0;
        long holdsFinishedInTheirOwnBatch = 0;
        long shortfallsOnTheirOwn = 0;
        long deadlinesExceeded = 0;
        for (long seed : new long[] {1, 7, 8123, 20260910}) {
            BatchEquivalence.Report onPostgres = BatchEquivalence.check(seed, () -> {
                PostgresFixture.reset();
                return new JdbcLedger(PostgresFixture.dataSource());
            });

            assertEquals(BatchEquivalence.check(seed, InMemoryLedger::new), onPostgres,
                    "the same history did something else on the ledger in a few maps");
            keysTwiceInABatch += onPostgres.keysTwiceInABatch();
            earlierHoldsFinished += onPostgres.earlierHoldsFinished();
            holdsFinishedInTheirOwnBatch += onPostgres.holdsFinishedInTheirOwnBatch();
            shortfallsOnTheirOwn += onPostgres.shortfallsOnTheirOwn();
            deadlinesExceeded += onPostgres.deadlinesExceeded();
        }

        assertTrue(keysTwiceInABatch > 0, "a key sent twice in one batch");
        assertTrue(earlierHoldsFinished > 0, "a hold from an earlier batch committed or released");
        assertTrue(holdsFinishedInTheirOwnBatch > 0, "a hold committed or released in the batch that took it");
        assertTrue(shortfallsOnTheirOwn > 0, "a shortfall decided on its own, with the expired holds");
        assertTrue(deadlinesExceeded > 0, "a caller already gone");
    }
}
