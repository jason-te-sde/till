package io.till.server;

import io.till.core.Outcome;

/**
 * Narrows an outcome to the one shape an endpoint can answer with.
 *
 * <p>A rejection becomes a {@link RejectedException} here, and {@link ApiExceptionHandler} turns that
 * into a problem body. That is the opposite of what the kernel does — there, a rejection is a value,
 * because a caller embedding the kernel has to handle it rather than catch it — and the boundary
 * between the two is this file.
 *
 * <p>The reason for the switch is worth stating: a controller writing
 * {@code (Outcome.Rejected) outcome} would compile after a new outcome kind is added to the kernel
 * and fail at runtime on the first request that produced one. Every method here is exhaustive over a
 * sealed type, so the same change fails to compile.
 */
final class Outcomes {

    private Outcomes() {}

    static Outcome.Reserved reserved(Outcome outcome) {
        return switch (outcome) {
            case Outcome.Reserved reserved -> reserved;
            case Outcome.Rejected rejected -> throw new RejectedException(rejected);
            default -> throw unexpected("reserve", outcome);
        };
    }

    static Outcome.Committed committed(Outcome outcome) {
        return switch (outcome) {
            case Outcome.Committed committed -> committed;
            case Outcome.Rejected rejected -> throw new RejectedException(rejected);
            default -> throw unexpected("commit", outcome);
        };
    }

    static Outcome.Released released(Outcome outcome) {
        return switch (outcome) {
            case Outcome.Released released -> released;
            case Outcome.Rejected rejected -> throw new RejectedException(rejected);
            default -> throw unexpected("release", outcome);
        };
    }

    static Outcome.Adjusted adjusted(Outcome outcome) {
        return switch (outcome) {
            case Outcome.Adjusted adjusted -> adjusted;
            case Outcome.Rejected rejected -> throw new RejectedException(rejected);
            default -> throw unexpected("adjust", outcome);
        };
    }

    private static IllegalStateException unexpected(String command, Outcome outcome) {
        return new IllegalStateException("a " + command + " cannot produce " + outcome);
    }
}
