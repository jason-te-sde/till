package io.till.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.stream.LongStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SimTest {

    @ParameterizedTest
    @ValueSource(longs = {1, 2, 3, 8123, 20260910})
    @DisplayName("every invariant holds after every step")
    void invariantsHold(long seed) {
        SimReport report = Sim.run(SimConfig.defaults(seed));

        assertEquals(report.steps() + report.drainSteps(), report.invariantChecks(), report.summary());
    }

    @Test
    @DisplayName("the same seed is the same run, down to the last row")
    void isDeterministic() {
        Sim first = new Sim(SimConfig.defaults(8123));
        Sim second = new Sim(SimConfig.defaults(8123));

        SimReport one = first.run();
        SimReport other = second.run();

        assertEquals(one, other);
        assertEquals(first.inspector().allStock(), second.inspector().allStock());
        assertEquals(first.inspector().allReservations(), second.inspector().allReservations());
        assertEquals(first.inspector().allEvents(), second.inspector().allEvents());
        assertEquals(first.history().observations(), second.history().observations());
    }

    @Test
    @DisplayName("a different seed is a different run")
    void seedsDiffer() {
        assertNotEquals(
                Sim.run(SimConfig.defaults(1)).summary(), Sim.run(SimConfig.defaults(2)).summary());
    }

    @Test
    @DisplayName("the run was actually hostile")
    void theRunWasHostile() {
        // The failure this asserts against is the one a simulation is most exposed to: a schedule
        // that happens to be tidy, passes every invariant, and would go on passing with the
        // concurrency control deleted. Each of these numbers is a fault the run must have produced.
        SimReport report = Sim.run(SimConfig.defaults(8123));

        assertTrue(report.conflicts() > 20, "callers barely contended: " + report.summary());
        assertTrue(report.replays() > 5, "no command was ever answered from the record: " + report.summary());
        assertTrue(report.crashesInjected() > 5, "nothing ever died mid-decision: " + report.summary());
        assertTrue(report.lostAcks() > 5, "every answer arrived: " + report.summary());
        assertTrue(report.duplicates() > 0, "nothing was ever re-sent: " + report.summary());
        assertTrue(report.expired() > 5, "no hold ever ran out of time: " + report.summary());
        assertTrue(report.sweeps() > 5, "the sweeper never ran: " + report.summary());
        assertTrue(report.committed() > 5, "nothing was ever sold: " + report.summary());
        assertTrue(report.outOfStock() > 0, "the SKUs never ran out: " + report.summary());
    }

    @Test
    @DisplayName("a hundred seeds, every invariant, every step")
    void manySeeds() {
        long checks =
                LongStream.rangeClosed(1, 100)
                        .map(seed -> Sim.run(SimConfig.defaults(seed).withSteps(500)).invariantChecks())
                        .sum();

        assertTrue(checks > 50_000, "expected tens of thousands of checks, got " + checks);
    }

    @Test
    @DisplayName("a caller always ends up idle, so the final state means something")
    void everyRunDrains() {
        Sim sim = new Sim(SimConfig.defaults(4));
        SimReport report = sim.run();

        assertTrue(report.drainSteps() > 0, "a run that ended with nothing in flight was not contended");
        assertTrue(
                sim.history().size() >= report.reserved(),
                "every hold taken was answered to somebody: " + report.summary());
    }

    @Test
    @DisplayName("a configuration that could not test anything is refused")
    void configIsValidated() {
        assertThrows(IllegalArgumentException.class, () -> SimConfig.defaults(1).withSteps(0));
        SimConfig base = SimConfig.defaults(1);
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new SimConfig(
                                1, 10, 1, 2, 10, 3, 1, base.ttl(), base.stepTime(), 0, base.timeJump(), 0, 0, 0, 0, 0, 0,
                                Flaw.NONE, base.start()));
    }
}
