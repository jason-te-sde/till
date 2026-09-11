package io.till.testkit;

import java.time.Duration;
import java.time.Instant;

/**
 * Everything about a run except the code under test.
 *
 * <p>One seed decides all of it: which client acts next, what it asks for, when a crash is injected,
 * when the clock jumps. Two runs with the same config are the same run, on any machine, which is
 * what makes a failure something to fix rather than something to shrug at.
 *
 * @param seed the only source of randomness in a run
 * @param steps how many phases to execute; a command takes at least three
 * @param clients how many callers act concurrently
 * @param skus how many SKUs they contend over
 * @param startingStock units each SKU is seeded with
 * @param maxLines most SKUs one reservation may cover
 * @param maxQuantity most units one line may ask for
 * @param ttl how long a hold lasts; short, so that expiry happens inside a run
 * @param stepTime how far the clock moves each step
 * @param timeJumpChance chance per step of a large jump instead, which is what makes holds expire in
 *     bunches the way a stalled process makes them expire in production
 * @param timeJump how far that jump goes
 * @param crashBeforeApplyChance chance that a decided command is thrown away before it is applied,
 *     as a process dying between deciding and committing would throw it away
 * @param lostAckChance chance that an applied command's answer never reaches the caller, so the
 *     caller retries something that has already happened — the case idempotency exists for
 * @param duplicateChance chance that a caller re-sends a command it has already completed
 * @param abandonChance chance that a caller with a hold simply walks away, leaving it to expire
 * @param adjustChance chance that an idle caller restocks instead of reserving
 * @param sweepChance chance per step that the background sweeper runs
 * @param flaw a known bug to inject, to check that the suite would catch it
 * @param start the instant the run begins at
 */
public record SimConfig(
        long seed,
        int steps,
        int clients,
        int skus,
        long startingStock,
        int maxLines,
        long maxQuantity,
        Duration ttl,
        Duration stepTime,
        double timeJumpChance,
        Duration timeJump,
        double crashBeforeApplyChance,
        double lostAckChance,
        double duplicateChance,
        double abandonChance,
        double adjustChance,
        double sweepChance,
        Flaw flaw,
        Instant start) {

    /** The instant every run starts at unless told otherwise. */
    public static final Instant EPOCH = Instant.parse("2026-01-01T00:00:00Z");

    public SimConfig {
        if (steps < 1) {
            throw new IllegalArgumentException("steps must be at least 1");
        }
        if (clients < 1) {
            throw new IllegalArgumentException("clients must be at least 1");
        }
        if (skus < 1) {
            throw new IllegalArgumentException("skus must be at least 1");
        }
        if (maxLines < 1 || maxLines > skus) {
            throw new IllegalArgumentException("maxLines must be between 1 and skus (" + skus + ")");
        }
        if (maxQuantity < 1) {
            throw new IllegalArgumentException("maxQuantity must be at least 1");
        }
        if (flaw == null || ttl == null || stepTime == null || timeJump == null || start == null) {
            throw new IllegalArgumentException("ttl, stepTime, timeJump, flaw and start are all required");
        }
    }

    /**
     * A configuration that contends hard on a handful of SKUs and injects every fault.
     *
     * <p>Tuned so that a run of a few thousand steps reliably produces conflicts, replays, expiries
     * and sweeps rather than a tidy sequence of successful reservations. {@link SimReport} is what
     * proves it did; {@code SimTest} asserts on it.
     *
     * @param seed the run's seed
     * @return the configuration
     */
    public static SimConfig defaults(long seed) {
        return new SimConfig(
                seed,
                3_000,
                8,
                4,
                40,
                2,
                4,
                Duration.ofSeconds(30),
                Duration.ofMillis(250),
                0.01,
                Duration.ofSeconds(45),
                0.05,
                0.08,
                0.05,
                0.15,
                0.10,
                0.02,
                Flaw.NONE,
                EPOCH);
    }

    /**
     * This configuration with a different seed.
     *
     * @param value the new seed
     * @return a copy
     */
    public SimConfig withSeed(long value) {
        return new SimConfig(
                value, steps, clients, skus, startingStock, maxLines, maxQuantity, ttl, stepTime,
                timeJumpChance, timeJump, crashBeforeApplyChance, lostAckChance, duplicateChance,
                abandonChance, adjustChance, sweepChance, flaw, start);
    }

    /**
     * This configuration with a different number of steps.
     *
     * @param value the new step count
     * @return a copy
     */
    public SimConfig withSteps(int value) {
        return new SimConfig(
                seed, value, clients, skus, startingStock, maxLines, maxQuantity, ttl, stepTime,
                timeJumpChance, timeJump, crashBeforeApplyChance, lostAckChance, duplicateChance,
                abandonChance, adjustChance, sweepChance, flaw, start);
    }

    /**
     * This configuration with a bug injected.
     *
     * @param value the flaw
     * @return a copy
     */
    public SimConfig withFlaw(Flaw value) {
        return new SimConfig(
                seed, steps, clients, skus, startingStock, maxLines, maxQuantity, ttl, stepTime,
                timeJumpChance, timeJump, crashBeforeApplyChance, lostAckChance, duplicateChance,
                abandonChance, adjustChance, sweepChance, value, start);
    }
}
