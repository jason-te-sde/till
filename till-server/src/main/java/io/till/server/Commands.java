package io.till.server;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.till.core.Command;
import io.till.core.ConflictException;
import io.till.core.DeadlineExceededException;
import io.till.core.Outcome;
import io.till.core.Till;
import java.time.DateTimeException;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * Runs a command and counts what happened.
 *
 * <p>Every command goes through here so that the metrics cannot drift from the traffic: a new
 * endpoint that forgets to instrument itself would have to go around this to do it.
 *
 * <p>The counters are deliberately about outcomes rather than about endpoints. "How many reservations
 * were refused for want of stock" is a question about the business; "how many POSTs returned 409" is
 * a question about the router, and only one of them tells an operator whether to order more stock.
 */
@Component
class Commands {

    /** The header a caller sends to say how long it will still wait for this attempt. */
    static final String TIMEOUT_HEADER = "Till-Timeout-Ms";

    private final Till till;
    private final MeterRegistry registry;

    Commands(Till till, MeterRegistry registry) {
        this.till = till;
        this.registry = registry;
    }

    /**
     * Runs one command with no deadline: the caller is assumed to wait however long it takes.
     *
     * @param command what to do
     * @return the outcome, including a rejection
     * @throws ConflictException if contention kept it from being applied
     */
    Outcome run(Command command) {
        return run(command, (String) null);
    }

    /**
     * Runs one command, bounded by the caller's own budget for it if it sent one.
     *
     * @param command what to do
     * @param timeoutHeader the {@code Till-Timeout-Ms} header as sent, or null if the caller sent
     *     none
     * @return the outcome, including a rejection
     * @throws IllegalArgumentException if the header was sent and is not a usable budget
     * @throws ConflictException if contention kept it from being applied
     * @throws DeadlineExceededException if the caller had stopped waiting before this could be
     *     decided or applied
     */
    Outcome run(Command command, String timeoutHeader) {
        Instant deadline = timeoutHeader == null ? null : deadlineFrom(timeoutHeader);
        Timer.Sample sample = Timer.start(registry);
        String kind = kindOf(command);
        try {
            Outcome outcome = deadline == null ? till.execute(command) : till.execute(command, deadline);
            registry.counter("till.outcome", "kind", kind, "outcome", describe(outcome)).increment();
            if (deadline != null && till.clock().instant().isAfter(deadline)) {
                // The pre-apply check in Till.execute passed, so the caller was still waiting when
                // apply() was called; this is the gap ADR 11 measures instead of closing: apply()
                // itself can wait, for a pooled connection or for its statements.
                registry.counter("till.late", "kind", kind, "outcome", describe(outcome)).increment();
            }
            return outcome;
        } catch (ConflictException e) {
            // Not an error in the command: the rows kept moving. Counted separately so that a
            // dashboard can tell contention from a service that is broken.
            registry.counter("till.outcome", "kind", kind, "outcome", "exhausted").increment();
            throw e;
        } catch (DeadlineExceededException e) {
            // Also not an error in the command: the caller's clock, not the rows, ran out. Counted
            // separately from "exhausted" so an operator can tell overload from contention.
            registry.counter("till.outcome", "kind", kind, "outcome", "deadline_exceeded").increment();
            throw e;
        } finally {
            sample.stop(registry.timer("till.command", "kind", kind));
        }
    }

    /**
     * Turns the header's value into an instant on the till's own clock.
     *
     * @param header the header as sent: expected to be a non-negative integer of milliseconds
     * @return {@code till.clock().instant()} plus that many milliseconds
     * @throws IllegalArgumentException if the header is not a non-negative integer, or is so large
     *     that adding it overflows what an {@link Instant} can represent
     */
    private Instant deadlineFrom(String header) {
        long millis;
        try {
            millis = Long.parseLong(header.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    TIMEOUT_HEADER + " must be a non-negative integer, got '" + header + "'", e);
        }
        if (millis < 0) {
            throw new IllegalArgumentException(TIMEOUT_HEADER + " must not be negative, got " + millis);
        }
        try {
            return till.clock().instant().plusMillis(millis);
        } catch (DateTimeException | ArithmeticException e) {
            throw new IllegalArgumentException(TIMEOUT_HEADER + " is too large: " + millis, e);
        }
    }

    private static String kindOf(Command command) {
        return switch (command) {
            case Command.Reserve ignored -> "reserve";
            case Command.Commit ignored -> "commit";
            case Command.Release ignored -> "release";
            case Command.Adjust ignored -> "adjust";
            case Command.Shard ignored -> "shard";
            case Command.Sweep ignored -> "sweep";
        };
    }

    private static String describe(Outcome outcome) {
        return switch (outcome) {
            case Outcome.Reserved ignored -> "reserved";
            case Outcome.Committed ignored -> "committed";
            case Outcome.Released ignored -> "released";
            case Outcome.Adjusted ignored -> "adjusted";
            case Outcome.Sharded ignored -> "sharded";
            case Outcome.Swept ignored -> "swept";
            // The rejection code, not the word "rejected": an operator wants to know that stock ran
            // out, and aggregating that with "you sent a key twice" loses the only useful signal.
            case Outcome.Rejected rejected -> rejected.code().name().toLowerCase(java.util.Locale.ROOT);
        };
    }
}
