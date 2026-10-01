package io.till.core;

import java.time.Instant;

/**
 * A command was not loaded, or not applied, because the caller had already stopped waiting for it.
 *
 * <p>Not a rejection, and not {@link ConflictException}: the rows may not have moved at all, and the
 * command may be perfectly valid. What makes this different from a rejection is that the kernel never
 * got to decide — or decided, but a caller that is no longer listening is not a caller a decision
 * should be written for. A server should answer this the way it answers {@link ConflictException}: a
 * "try again" status, with a fresh deadline next time, rather than a 500 that would page somebody
 * about a customer who gave up.
 */
public class DeadlineExceededException extends RuntimeException {

    private final transient Command command;
    private final transient Instant deadline;

    /**
     * @param command the command that was not loaded, or not applied
     * @param deadline the instant, on the ledger's own clock, that had already passed
     */
    public DeadlineExceededException(Command command, Instant deadline) {
        super(
                "the caller's deadline ("
                        + deadline
                        + ") had already passed for "
                        + command.getClass().getSimpleName());
        this.command = command;
        this.deadline = deadline;
    }

    /**
     * The command that was not loaded, or not applied.
     *
     * @return the command
     */
    public Command command() {
        return command;
    }

    /**
     * The instant, on the ledger's own clock, that had already passed.
     *
     * @return the deadline
     */
    public Instant deadline() {
        return deadline;
    }
}
