package io.till.server;

import io.till.core.ConflictException;
import io.till.core.IncompleteSnapshotException;
import io.till.jdbc.LedgerException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Everything that is not an outcome.
 *
 * <p>The distinction this file exists to keep: a rejection is an answer and comes back from the
 * kernel, while these are failures of the machinery around it. The one that matters is
 * {@link ConflictException}, which is <b>not</b> an error in the request — the rows kept moving under
 * a perfectly good command — and so answers 503 with a {@code Retry-After} rather than a 500 that
 * would page somebody about contention.
 */
@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /**
     * @param e the command that could not be applied
     * @return 503, with a hint about when to come back
     */
    @ExceptionHandler(ConflictException.class)
    ResponseEntity<ProblemDetail> onConflict(ConflictException e) {
        LOG.info("giving up after {} attempts: {}", e.attempts(), e.command());
        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "too many callers reached these rows first; the request is fine and can be retried");
        problem.setTitle("Contention");
        problem.setProperty("code", "CONTENTION");
        problem.setProperty("attempts", e.attempts());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "1")
                .body(problem);
    }

    /**
     * @param e a command the kernel refused
     * @return the status this rejection maps to, with the reason in the body
     */
    @ExceptionHandler(RejectedException.class)
    ResponseEntity<ProblemDetail> onRejected(RejectedException e) {
        ProblemDetail problem = Problems.of(e.rejected());
        return ResponseEntity.status(Problems.statusFor(e.rejected().code())).body(problem);
    }

    /**
     * @param e something looked up by id that is not there
     * @return 404
     */
    @ExceptionHandler(NotFoundException.class)
    ResponseEntity<ProblemDetail> onNotFound(NotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Problems.of(HttpStatus.NOT_FOUND, e.title(), e.getMessage()));
    }

    /**
     * @param e a malformed identifier, a quantity of zero, a time to live beyond the maximum
     * @return 400
     */
    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ProblemDetail> onBadRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Problems.of(HttpStatus.BAD_REQUEST, "Bad request", e.getMessage()));
    }

    /**
     * @param e the ledger did not load what a command needed
     * @return 500, because this is a bug here and not a mistake by the caller
     */
    @ExceptionHandler(IncompleteSnapshotException.class)
    ResponseEntity<ProblemDetail> onIncompleteSnapshot(IncompleteSnapshotException e) {
        LOG.error("the ledger returned an incomplete snapshot, which is a bug in till", e);
        return ResponseEntity.internalServerError()
                .body(
                        Problems.of(
                                HttpStatus.INTERNAL_SERVER_ERROR,
                                "Internal error",
                                "the ledger did not return what was needed"));
    }

    /**
     * @param e the database could not be reached, or refused something impossible
     * @return 503
     */
    @ExceptionHandler(LedgerException.class)
    ResponseEntity<ProblemDetail> onLedger(LedgerException e) {
        LOG.error("the ledger failed", e);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(
                        Problems.of(
                                HttpStatus.SERVICE_UNAVAILABLE,
                                "Ledger unavailable",
                                "the ledger could not be reached"));
    }

    /**
     * @param e a mutating request with no {@code Idempotency-Key}
     * @return 400, saying which header and why it is not optional
     */
    @ExceptionHandler(MissingRequestHeaderException.class)
    ResponseEntity<ProblemDetail> onMissingHeader(MissingRequestHeaderException e) {
        return ResponseEntity.badRequest()
                .body(
                        Problems.of(
                                HttpStatus.BAD_REQUEST,
                                "Missing header",
                                e.getHeaderName()
                                        + " is required on every request that changes something, so that "
                                        + "retrying one is safe"));
    }
}
