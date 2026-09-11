package io.till.core;

import java.time.Instant;

/**
 * What a key was used for, so that using it again returns the same answer.
 *
 * <p>Stored with a unique constraint on {@link #key()}. That constraint is not decoration: it is how
 * two copies of the same request arriving at two servers at the same instant resolve. Both load a
 * snapshot with no record, both decide to act, and both try to insert. One transaction wins; the
 * other is refused by the constraint, reloads, finds the record, and replays it. Neither the
 * database nor the kernel needs a lock for that to be true.
 *
 * @param key the caller's key
 * @param fingerprint {@link Command#fingerprint()} of the request that was executed
 * @param encodedOutcome the outcome, as written by {@link Codec#encodeOutcome}
 * @param recordedAt when the outcome was decided
 */
public record OutcomeRecord(IdempotencyKey key, String fingerprint, String encodedOutcome, Instant recordedAt) {

    public OutcomeRecord {
        if (key == null) {
            throw new IllegalArgumentException("idempotency key must not be null");
        }
        if (fingerprint == null || fingerprint.isBlank()) {
            throw new IllegalArgumentException("fingerprint must not be blank");
        }
        if (encodedOutcome == null || encodedOutcome.isBlank()) {
            throw new IllegalArgumentException("encoded outcome must not be blank");
        }
        if (recordedAt == null) {
            throw new IllegalArgumentException("recordedAt must not be null");
        }
    }

    /**
     * Records an outcome under a command's key.
     *
     * @param command the command that was executed
     * @param outcome what it did
     * @param now the decision instant
     * @return the record to insert
     */
    public static OutcomeRecord of(Command command, Outcome outcome, Instant now) {
        IdempotencyKey key =
                command.idempotencyKey()
                        .orElseThrow(() -> new IllegalArgumentException("cannot record an outcome for a keyless command"));
        return new OutcomeRecord(key, command.fingerprint(), Codec.encodeOutcome(outcome), now);
    }

    /**
     * The recorded outcome, decoded.
     *
     * @return the outcome the first execution returned
     */
    public Outcome outcome() {
        return Codec.decodeOutcome(encodedOutcome);
    }
}
