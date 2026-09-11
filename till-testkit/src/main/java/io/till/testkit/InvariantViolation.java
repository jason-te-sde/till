package io.till.testkit;

/**
 * A property that must always hold did not.
 *
 * <p>The message carries the seed and the step, because the only useful thing to do with one of
 * these is to run that seed again with logging turned up. A simulation whose failures are not
 * reproducible is a random number generator with an opinion.
 */
public class InvariantViolation extends AssertionError {

    private final long seed;
    private final long step;

    /**
     * @param property which invariant broke, in the words the documentation uses for it
     * @param seed the run's seed
     * @param step which step it broke on
     * @param detail the numbers that prove it broke
     */
    public InvariantViolation(String property, long seed, long step, String detail) {
        super(property + " violated at step " + step + " (seed " + seed + "): " + detail);
        this.seed = seed;
        this.step = step;
    }

    /**
     * The seed that reproduces this.
     *
     * @return the seed
     */
    public long seed() {
        return seed;
    }

    /**
     * The step it broke on.
     *
     * @return the step
     */
    public long step() {
        return step;
    }
}
