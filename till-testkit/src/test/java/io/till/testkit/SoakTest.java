package io.till.testkit;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * A wide sweep of seeds, off by default.
 *
 * <p>Off because it is minutes rather than seconds and every pull request would pay for it. On in
 * the nightly workflow, where it sweeps far more seeds than a pull request could afford — which is
 * exactly where the interesting bugs are, since the cheap ones are already covered by
 * {@link SimTest}.
 *
 * <pre>{@code
 * mvn test -pl till-testkit -Dtill.sim.seeds=10000 -Dtest=SoakTest \
 *     -Dsurefire.failIfNoSpecifiedTests=false
 * }</pre>
 */
class SoakTest {

    @Test
    @EnabledIfSystemProperty(named = "till.sim.seeds", matches = "\\d+")
    @DisplayName("sweep a range of seeds, checking every invariant after every step")
    void soak() {
        int seeds = Integer.getInteger("till.sim.seeds", 0);
        long checks = 0;
        long conflicts = 0;
        long answers = 0;
        long start = System.nanoTime();

        for (int seed = 1; seed <= seeds; seed++) {
            SimReport report = Sim.run(SimConfig.defaults(seed));
            checks += report.invariantChecks();
            conflicts += report.conflicts();
            answers += report.observations();
        }

        double seconds = (System.nanoTime() - start) / 1e9;
        System.out.printf(
                "soak: %d seeds, %d invariant checks, %d conflicts, %d answers, %.1fs (%.0f steps/s)%n",
                seeds, checks, conflicts, answers, seconds, checks / seconds);

        assertTrue(conflicts > seeds, "a sweep that barely contended proves little: " + conflicts);
    }
}
