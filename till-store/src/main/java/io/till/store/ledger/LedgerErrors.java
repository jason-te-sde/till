package io.till.store.ledger;

import io.till.client.TillApiException;
import io.till.core.Outcome;
import io.till.core.RejectionCode;
import io.till.core.Sku;
import java.io.UncheckedIOException;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * How a failed call to the ledger is classified. Three outcomes, and they must not blur.
 *
 * <ul>
 *   <li><b>A refusal</b> — the problem body carried a rejection code. The ledger understood the
 *       request and said no: out of stock, already paid, expired. That is an answer.
 *   <li><b>Unavailable</b> — the ledger was unreachable, or answered 5xx after the client's own
 *       retries. Nobody knows what the answer would have been. Safe to retry, and said so with a 503.
 *   <li><b>Refused the store itself</b> — a 4xx with no rejection code: a wrong token, a wrong base
 *       URL. Not the customer's fault and not transient, so it is neither of the above; it is a 500
 *       and an error in the log, because only a deployment change fixes it.
 * </ul>
 */
final class LedgerErrors {

    private LedgerErrors() {}

    /**
     * Runs a call, turning a refusal into a value.
     *
     * @param what what is being attempted, for the log line
     * @param call the call
     * @return what the call returned, or the refusal
     */
    static Outcome outcomeOf(String what, Supplier<? extends Outcome> call) {
        try {
            return call.get();
        } catch (TillApiException e) {
            Optional<Outcome.Rejected> refusal = refusal(e);
            if (refusal.isPresent()) {
                return refusal.get();
            }
            throw classify(what, e);
        } catch (UncheckedIOException e) {
            throw new LedgerUnavailableException(what + ": the ledger could not be reached", e);
        }
    }

    /**
     * Runs a call whose refusal is the caller's answer rather than something to branch on.
     *
     * @param what what is being attempted
     * @param call the call
     * @param <T> what it returns
     * @return what it returned
     * @throws LedgerRejection if the ledger refused
     */
    static <T> T valueOf(String what, Supplier<T> call) {
        try {
            return call.get();
        } catch (TillApiException e) {
            Optional<Outcome.Rejected> refusal = refusal(e);
            if (refusal.isPresent()) {
                throw new LedgerRejection(refusal.get());
            }
            throw classify(what, e);
        } catch (UncheckedIOException e) {
            throw new LedgerUnavailableException(what + ": the ledger could not be reached", e);
        }
    }

    private static Optional<Outcome.Rejected> refusal(TillApiException e) {
        Optional<RejectionCode> code = e.rejection();
        return code.map(c -> new Outcome.Rejected(
                c,
                e.detail(),
                e.shortfalls().stream()
                        .map(s -> new Outcome.Shortfall(Sku.of(s.sku()), s.requested(), s.available()))
                        .toList()));
    }

    private static RuntimeException classify(String what, TillApiException e) {
        if (e.status() >= 500) {
            return new LedgerUnavailableException(what + ": " + e.getMessage(), e);
        }
        return new IllegalStateException(
                "the ledger refused the store itself while " + what + " — check store.till.base-url and the tokens: "
                        + e.getMessage(),
                e);
    }
}
