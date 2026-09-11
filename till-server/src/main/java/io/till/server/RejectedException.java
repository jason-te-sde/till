package io.till.server;

import io.till.core.Outcome;

/**
 * A command the kernel refused, on its way to a status code.
 *
 * <p>Exists so that the mapping from a {@link io.till.core.RejectionCode} to a status lives in one
 * place instead of at every endpoint. The kernel itself returns rejections as values and throws
 * nothing; this is the web layer's translation of that value, and it goes no further than
 * {@link ApiExceptionHandler}.
 */
class RejectedException extends RuntimeException {

    private final transient Outcome.Rejected rejected;

    RejectedException(Outcome.Rejected rejected) {
        super(rejected.code() + ": " + rejected.detail());
        this.rejected = rejected;
    }

    Outcome.Rejected rejected() {
        return rejected;
    }
}
