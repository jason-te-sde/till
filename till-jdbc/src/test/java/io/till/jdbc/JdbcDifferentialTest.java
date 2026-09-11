package io.till.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.till.core.ReservationState;
import io.till.core.mem.InMemoryLedger;
import java.util.Optional;
import io.till.testkit.Sim;
import io.till.testkit.SimConfig;
import io.till.testkit.SimReport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The same seeded schedule against both ledgers, compared row for row.
 *
 * <p>Worth more than either implementation's own tests. The two were written from one contract by one
 * person, which means they are wrong in the same places only where the contract itself is unclear —
 * and they disagree exactly where one of them read it differently. Ordering, boundary conditions,
 * what counts as a conflict, whether an absent row is the same as an empty one: every one of those is
 * a place two implementations drift, and none of them is visible from inside either.
 *
 * <p>Because the simulator's schedule is a function of the seed and of the answers it gets, identical
 * final states are also evidence that every answer along the way matched. A divergence at step 40
 * changes what the caller does at step 41, and the run comes apart from there.
 *
 * <p>Deliberately short runs. Every step here is two real transactions, so this is about a second per
 * seed rather than a millisecond; the long sweeps belong in the in-memory soak, and what this has to
 * establish is that the adapter agrees, not that the rules are right.
 */
// Suites that share the one database run one at a time. Everything else in the build still runs in
// parallel; these truncate the tables they are about, a truncate locks the whole table, and two of
// them at once is a deadlock rather than a race.
@ResourceLock("till-database")
@ExtendWith(RequiresDatabase.class)
class JdbcDifferentialTest {

    @BeforeEach
    void setUp() {
        PostgresFixture.reset();
    }

    @ParameterizedTest
    @ValueSource(longs = {1, 7, 8123})
    @DisplayName("PostgreSQL and the in-memory ledger end up in the same state, row for row")
    void ledgersAgree(long seed) {
        SimConfig config = SimConfig.defaults(seed).withSteps(400);

        InMemoryLedger memory = new InMemoryLedger();
        Sim inMemory = new Sim(config, memory);
        SimReport memoryReport = inMemory.run();

        PostgresFixture.reset();
        JdbcLedger postgres = new JdbcLedger(PostgresFixture.dataSource());
        Sim onPostgres = new Sim(config, postgres);
        SimReport postgresReport = onPostgres.run();

        assertEquals(memoryReport, postgresReport, "the two runs did not even do the same things");
        assertEquals(memory.allStock(), postgres.allStock());
        assertEquals(memory.allReservations(), postgres.allReservations());
        assertEquals(
                memory.allEvents().stream().map(e -> e.sequence() + " " + e.dedupeKey()).toList(),
                postgres.allEvents().stream().map(e -> e.sequence() + " " + e.dedupeKey()).toList());
        assertEquals(inMemory.history().observations(), onPostgres.history().observations());

        // The listings are read paths the simulator never exercises, and they are the two places the
        // adapters are most likely to drift: ordering and paging.
        assertEquals(
                memory.listStock(Optional.empty(), 100), postgres.listStock(Optional.empty(), 100));
        for (ReservationState state : ReservationState.values()) {
            assertEquals(
                    memory.listReservations(Optional.of(state), 100),
                    postgres.listReservations(Optional.of(state), 100),
                    "the two ledgers disagree about " + state + " reservations");
        }
        assertTrue(memoryReport.conflicts() > 0, "a schedule with no contention compares nothing: " + memoryReport.summary());
    }
}
