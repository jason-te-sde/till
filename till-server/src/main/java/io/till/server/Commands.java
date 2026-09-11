package io.till.server;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.till.core.Command;
import io.till.core.ConflictException;
import io.till.core.Outcome;
import io.till.core.Till;
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

    private final Till till;
    private final MeterRegistry registry;

    Commands(Till till, MeterRegistry registry) {
        this.till = till;
        this.registry = registry;
    }

    /**
     * Runs one command.
     *
     * @param command what to do
     * @return the outcome, including a rejection
     * @throws ConflictException if contention kept it from being applied
     */
    Outcome run(Command command) {
        Timer.Sample sample = Timer.start(registry);
        String kind = kindOf(command);
        try {
            Outcome outcome = till.execute(command);
            registry.counter("till.outcome", "kind", kind, "outcome", describe(outcome)).increment();
            return outcome;
        } catch (ConflictException e) {
            // Not an error in the command: the rows kept moving. Counted separately so that a
            // dashboard can tell contention from a service that is broken.
            registry.counter("till.outcome", "kind", kind, "outcome", "exhausted").increment();
            throw e;
        } finally {
            sample.stop(registry.timer("till.command", "kind", kind));
        }
    }

    private static String kindOf(Command command) {
        return switch (command) {
            case Command.Reserve ignored -> "reserve";
            case Command.Commit ignored -> "commit";
            case Command.Release ignored -> "release";
            case Command.Adjust ignored -> "adjust";
            case Command.Sweep ignored -> "sweep";
        };
    }

    private static String describe(Outcome outcome) {
        return switch (outcome) {
            case Outcome.Reserved ignored -> "reserved";
            case Outcome.Committed ignored -> "committed";
            case Outcome.Released ignored -> "released";
            case Outcome.Adjusted ignored -> "adjusted";
            case Outcome.Swept ignored -> "swept";
            // The rejection code, not the word "rejected": an operator wants to know that stock ran
            // out, and aggregating that with "you sent a key twice" loses the only useful signal.
            case Outcome.Rejected rejected -> rejected.code().name().toLowerCase(java.util.Locale.ROOT);
        };
    }
}
