package io.till.store.ledger;

/**
 * The ledger could not be asked.
 *
 * <p>Not a refusal — the store does not know what the answer would have been. Surfaced as a 503 with
 * a {@code Retry-After}, because the customer's request was fine and will very likely succeed in a
 * moment, and because every write the store makes carries an idempotency key, so retrying it is safe.
 */
public class LedgerUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * @param message what was being attempted
     * @param cause what went wrong underneath
     */
    public LedgerUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
