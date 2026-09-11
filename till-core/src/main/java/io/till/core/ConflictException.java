package io.till.core;

/**
 * A command could not be applied because other callers kept reaching the same rows first.
 *
 * <p>Not a rejection. The command may be perfectly valid and will very likely succeed if tried
 * again; what failed is the optimistic concurrency control, after {@link Till} exhausted its
 * attempts. A server should answer this with a "try again" status and a retryable error, and an
 * operator seeing a lot of it should look at how many callers are contending on one SKU rather than
 * at their data.
 */
public class ConflictException extends RuntimeException {

    private final transient Command command;
    private final int attempts;

    /**
     * @param command the command that could not be applied
     * @param attempts how many times it was decided and refused
     */
    public ConflictException(Command command, int attempts) {
        super("gave up applying " + command.getClass().getSimpleName() + " after " + attempts + " attempts");
        this.command = command;
        this.attempts = attempts;
    }

    /**
     * The command that could not be applied.
     *
     * @return the command
     */
    public Command command() {
        return command;
    }

    /**
     * How many attempts were made.
     *
     * @return the attempt count
     */
    public int attempts() {
        return attempts;
    }
}
