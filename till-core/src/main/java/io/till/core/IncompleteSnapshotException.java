package io.till.core;

/**
 * A {@link Ledger} handed the kernel a snapshot that did not contain something the command needed.
 *
 * <p>Not a rejection: the command may well have been fine. This is the adapter's contract being
 * broken, and the only correct response is to fix the adapter, so it is thrown rather than returned.
 * Turning it into an outcome would let a query that forgot a join look like a customer who ran out
 * of stock.
 *
 * @see Ledger#load
 */
public class IncompleteSnapshotException extends IllegalArgumentException {

    /**
     * @param message what was missing and which command needed it
     */
    public IncompleteSnapshotException(String message) {
        super(message);
    }
}
