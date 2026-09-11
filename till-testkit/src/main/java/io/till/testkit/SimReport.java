package io.till.testkit;

import java.time.Instant;

/**
 * What a run actually did.
 *
 * <p>Exists so that a test can assert the run was hostile rather than only that it passed. A
 * simulation of eight contending callers that produced no conflicts, no replays and no expiries
 * tested the happy path eight times in a row, and would go on passing after the concurrency control
 * was deleted. Every chaos test in this project asserts on these numbers.
 *
 * @param seed the run's seed
 * @param steps phases executed before draining
 * @param drainSteps phases needed afterwards to let every caller finish
 * @param invariantChecks how many times every property was verified
 * @param observations answers given to callers
 * @param conflicts decisions refused because a version had moved
 * @param replays commands answered from the idempotency record without writing
 * @param crashesInjected decisions thrown away between deciding and applying
 * @param lostAcks answers that never reached the caller, forcing a retry of something that happened
 * @param duplicates commands deliberately re-sent after completing
 * @param reserved holds taken
 * @param committed holds turned into sales
 * @param released holds given back
 * @param expired holds written off for running out of time
 * @param adjusted direct stock changes
 * @param sweeps background sweeps run
 * @param rejections commands refused by the rules
 * @param outOfStock of those, ones refused for want of stock
 * @param endedAt the simulated instant the run finished at
 */
public record SimReport(
        long seed,
        long steps,
        long drainSteps,
        long invariantChecks,
        long observations,
        long conflicts,
        long replays,
        long crashesInjected,
        long lostAcks,
        long duplicates,
        long reserved,
        long committed,
        long released,
        long expired,
        long adjusted,
        long sweeps,
        long rejections,
        long outOfStock,
        Instant endedAt) {

    /**
     * One line for a log or a failure message.
     *
     * @return the counts, in a fixed order
     */
    public String summary() {
        return "seed=" + seed
                + " steps=" + steps + "+" + drainSteps
                + " checks=" + invariantChecks
                + " answers=" + observations
                + " conflicts=" + conflicts
                + " replays=" + replays
                + " crashes=" + crashesInjected
                + " lostAcks=" + lostAcks
                + " duplicates=" + duplicates
                + " reserved=" + reserved
                + " committed=" + committed
                + " released=" + released
                + " expired=" + expired
                + " adjusted=" + adjusted
                + " sweeps=" + sweeps
                + " rejections=" + rejections + " (outOfStock=" + outOfStock + ")";
    }
}
