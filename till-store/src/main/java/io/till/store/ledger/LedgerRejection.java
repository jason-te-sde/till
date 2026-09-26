package io.till.store.ledger;

import io.till.core.Outcome;

/**
 * The ledger said no, and the caller has nothing better to do than pass that on.
 *
 * <p>Most code in the store looks at a refusal and decides what it means for an order — see
 * {@code OrderService}. This is for the places where the refusal <i>is</i> the answer: the operator
 * console, and a checkout the customer has to be told about.
 */
public class LedgerRejection extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient Outcome.Rejected rejected;

    /**
     * @param rejected what the ledger said
     */
    public LedgerRejection(Outcome.Rejected rejected) {
        super(rejected.code() + ": " + rejected.detail());
        this.rejected = rejected;
    }

    /**
     * @return what the ledger said
     */
    public Outcome.Rejected rejected() {
        return rejected;
    }
}
