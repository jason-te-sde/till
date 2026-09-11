package io.till.testkit;

/**
 * A bug to put back in on purpose.
 *
 * <p>A test suite that passes proves nothing about the suite. Every one of these is a mistake a
 * hand-written implementation of this problem plausibly makes, and {@code FlawDetectionTest} asserts
 * that a run with it injected <b>fails</b>, naming which check caught it. Without that, a clean
 * verdict on the correct implementation would be a statement about the implementation only.
 *
 * <p>Two mistakes are deliberately <i>not</i> here, because the design makes them harmless and it is
 * worth knowing which:
 *
 * <ul>
 *   <li>Applying a decision twice. Mutations carry absolute values rather than deltas, so writing
 *       them again writes the same numbers.
 *   <li>A ledger that offers a live hold as reclaimable. The kernel re-checks the deadline itself
 *       and skips it, because it does not trust the adapter's filtering.
 * </ul>
 *
 * @see FlawedLedger
 */
public enum Flaw {

    /** No bug. */
    NONE,

    /**
     * Expected versions are not checked, so the later of two decisions made against the same row
     * overwrites the earlier one.
     *
     * <p>The classic read-modify-write race, and the reason the stock table has a version column at
     * all. Two callers both see 10 available, both decide to take 6, and both write their result.
     */
    LOST_UPDATE,

    /**
     * A recorded outcome is never returned, so a retried command executes a second time.
     *
     * <p>What a service without an idempotency table does. Every timed-out checkout that a client
     * retries becomes a second hold on stock.
     */
    NO_IDEMPOTENCY,

    /**
     * Mutations are written and the events beside them are dropped.
     *
     * <p>What publishing outside the transaction looks like from the database's side: the state is
     * right and everything downstream is quietly wrong.
     */
    PARTIAL_APPLY,

    /**
     * Stock rows are loaded with {@code reserved} forced to zero, so availability looks like the
     * whole on-hand level.
     *
     * <p>A query that forgot to select a column, or an availability check written as
     * {@code stock >= quantity}, which is exactly how it is written in most tutorials.
     */
    RESERVED_IGNORED
}
