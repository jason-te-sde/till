package io.till.core;

/**
 * A caller's name for one attempt at a command.
 *
 * <p>The contract is the one HTTP clients already expect: a command carrying a key that has been
 * seen before is not executed again, and the outcome recorded the first time is returned instead.
 * A key reused with a <i>different</i> request is rejected rather than served, because the two
 * requests cannot both be the one the key names and guessing which is meant is how a retry
 * quietly becomes a second order.
 *
 * <p>Keys are scoped to the whole ledger, not to a SKU or a reservation. A caller that wants them
 * scoped can put the scope in the key.
 *
 * @param value the key, at most 128 characters from {@code A-Za-z0-9._:@=+/-}
 */
public record IdempotencyKey(String value) {

    /** Longest accepted key, in characters. */
    public static final int MAX_LENGTH = 128;

    public IdempotencyKey {
        Ids.check("idempotency key", value, MAX_LENGTH);
    }

    /**
     * Equivalent to the constructor, for call sites where it reads better.
     *
     * @param value the key
     * @return the idempotency key
     */
    public static IdempotencyKey of(String value) {
        return new IdempotencyKey(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
