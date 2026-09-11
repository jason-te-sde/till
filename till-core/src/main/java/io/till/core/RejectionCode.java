package io.till.core;

/**
 * Why a command was refused.
 *
 * <p>A rejection is an answer, not a failure: the kernel considered the command and decided it may
 * not happen. Failures — a snapshot that did not contain what the command needed, a ledger that
 * could not be reached — are exceptions instead, because a caller can retry those and cannot retry
 * these.
 *
 * <p>No code here carries an HTTP status. The mapping lives in the server module, so that a caller
 * embedding the kernel in something that is not a web service does not inherit one.
 */
public enum RejectionCode {

    /** Not enough available stock for every line of the command, so none of it happened. */
    INSUFFICIENT_STOCK,

    /** A SKU with no row in the ledger. Adjust it into existence before reserving it. */
    UNKNOWN_SKU,

    /** No reservation with that id. */
    RESERVATION_NOT_FOUND,

    /** The hold ran out of time before the command arrived. */
    RESERVATION_EXPIRED,

    /** The goods already left; there is nothing left to commit or to give back. */
    ALREADY_COMMITTED,

    /** The hold was already given up, and asking again does not re-take it. */
    ALREADY_RELEASED,

    /** A reservation with that id already exists. Ids are the caller's to keep unique. */
    RESERVATION_ID_IN_USE,

    /**
     * The key has been used before, for a request that was not this one.
     *
     * <p>The only rejection that is never recorded under its own key: recording it would make the
     * mistake permanent, and the caller's real request would then be refused forever.
     */
    IDEMPOTENCY_KEY_REUSED
}
