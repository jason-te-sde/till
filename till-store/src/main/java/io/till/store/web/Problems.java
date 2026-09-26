package io.till.store.web;

import io.till.core.Outcome;
import io.till.core.RejectionCode;
import io.till.store.ledger.LedgerRejection;
import io.till.store.ledger.LedgerUnavailableException;
import io.till.store.orders.OrderClosedException;
import io.till.store.orders.OrderService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Every failure, as an RFC 9457 problem with a machine-readable {@code code}.
 *
 * <p>The code matters more than the status. A 409 is "somebody bought the last copy first" or "this
 * order is already paid for", and a storefront that can only see 409 shows the same sentence for both —
 * neither of which helps. With {@code INSUFFICIENT_STOCK} and its {@code shortfalls}, the basket page
 * can offer the copies that are left instead of a dead end.
 *
 * <p>The ledger's refusals are passed through rather than rewritten: its code, its sentence, and its
 * per-game shortfalls. Flattening them into "sorry, something went wrong" would throw away the only
 * information the customer can act on.
 */
@RestControllerAdvice
class Problems extends ResponseEntityExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(Problems.class);

    @ExceptionHandler(NotFoundException.class)
    ResponseEntity<ProblemDetail> notFound(NotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, e.title(), e.getMessage(), "NOT_FOUND");
    }

    @ExceptionHandler(LedgerRejection.class)
    ResponseEntity<ProblemDetail> rejected(LedgerRejection e) {
        Outcome.Rejected rejected = e.rejected();
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(statusFor(rejected.code()), rejected.detail());
        problem.setTitle(titleFor(rejected.code()));
        problem.setProperty("code", rejected.code().name());
        if (!rejected.shortfalls().isEmpty()) {
            problem.setProperty("shortfalls", rejected.shortfalls().stream()
                    .map(s -> new Problem.Shortfall(s.sku().value(), s.requested(), s.available()))
                    .toList());
        }
        return ResponseEntity.status(problem.getStatus()).body(problem);
    }

    @ExceptionHandler(OrderService.KeyReusedException.class)
    ResponseEntity<ProblemDetail> keyReused(OrderService.KeyReusedException e) {
        return problem(HttpStatus.UNPROCESSABLE_CONTENT, "Idempotency key reused", e.getMessage(), "IDEMPOTENCY_KEY_REUSED");
    }

    @ExceptionHandler(OrderClosedException.class)
    ResponseEntity<ProblemDetail> closed(OrderClosedException e) {
        return switch (e.status()) {
            // Gone, not conflict: the hold existed, the customer had it, and it is no longer
            // available — which is what 410 means.
            case EXPIRED -> problem(HttpStatus.GONE, "Order expired", e.getMessage(), "ORDER_EXPIRED");
            case CANCELLED -> problem(HttpStatus.CONFLICT, "Order cancelled", e.getMessage(), "ORDER_CANCELLED");
            case PAID -> problem(HttpStatus.CONFLICT, "Order already paid", e.getMessage(), "ORDER_PAID");
            case PENDING -> problem(HttpStatus.CONFLICT, "Order pending", e.getMessage(), "ORDER_PENDING");
        };
    }

    @ExceptionHandler(LedgerUnavailableException.class)
    ResponseEntity<ProblemDetail> unavailable(LedgerUnavailableException e) {
        LOG.warn("the ledger is unavailable: {}", e.getMessage());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.SERVICE_UNAVAILABLE, "the store could not reach its stock system; your request is safe to retry");
        problem.setTitle("Temporarily unavailable");
        problem.setProperty("code", "LEDGER_UNAVAILABLE");
        // Safe to retry because every write here carries an idempotency key, which is the only reason
        // it is honest to say so.
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).header(HttpHeaders.RETRY_AFTER, "2").body(problem);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ProblemDetail> badRequest(IllegalArgumentException e) {
        return problem(HttpStatus.BAD_REQUEST, "Bad request", e.getMessage(), "BAD_REQUEST");
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException e, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        // Every field that failed, not only the first, so a client fixes a form in one round trip.
        List<Problem.FieldError> errors = e.getBindingResult().getFieldErrors().stream()
                .map(error -> new Problem.FieldError(
                        error.getField(), error.getDefaultMessage() == null ? "is invalid" : error.getDefaultMessage()))
                .toList();
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "the request body is not valid");
        problem.setTitle("Bad request");
        problem.setProperty("code", "INVALID_BODY");
        problem.setProperty("errors", errors);
        return ResponseEntity.badRequest().body(problem);
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    ResponseEntity<ProblemDetail> missingHeader(MissingRequestHeaderException e) {
        return problem(
                HttpStatus.BAD_REQUEST,
                "Missing header",
                e.getHeaderName() + " is required on every request that changes something, so that retrying one is safe",
                "MISSING_HEADER");
    }

    /**
     * Anything nobody anticipated: a 500 in the same shape as every other failure, and the stack trace
     * in the log rather than in the response.
     *
     * <p>Without this, an unexpected exception falls through to the servlet container's error page —
     * a different JSON shape, with no {@code code}, which the SPA would have to special-case.
     */
    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> unexpected(Exception e, HttpServletRequest request) {
        LOG.error("unhandled failure on {} {}", request.getMethod(), request.getRequestURI(), e);
        return problem(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Something went wrong",
                "the store failed to handle this request, and has logged why",
                "INTERNAL_ERROR");
    }

    /**
     * Every problem the framework builds for itself — a method not allowed, a body that is not JSON, a
     * path that does not exist — gets a {@code code} as well, named after its status. The contract says
     * every problem has one, and a client should not have to learn which ones do.
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception e, Object body, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ResponseEntity<Object> response = super.handleExceptionInternal(e, body, headers, status, request);
        if (response != null
                && response.getBody() instanceof ProblemDetail problem
                && (problem.getProperties() == null || !problem.getProperties().containsKey("code"))) {
            HttpStatus known = HttpStatus.resolve(status.value());
            problem.setProperty("code", known == null ? "HTTP_" + status.value() : known.name());
        }
        return response;
    }

    private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String title, String detail, String code) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        problem.setProperty("code", code);
        return ResponseEntity.status(status).body(problem);
    }

    private static HttpStatus statusFor(RejectionCode code) {
        return switch (code) {
            // A game the catalogue sells but the ledger has never stocked is, to a customer, simply not
            // available — the same answer as "none left", not a 404 for something they can see.
            case INSUFFICIENT_STOCK, UNKNOWN_SKU, ALREADY_COMMITTED, ALREADY_RELEASED, RESERVATION_ID_IN_USE ->
                    HttpStatus.CONFLICT;
            case RESERVATION_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case RESERVATION_EXPIRED -> HttpStatus.GONE;
            case IDEMPOTENCY_KEY_REUSED -> HttpStatus.UNPROCESSABLE_CONTENT;
        };
    }

    private static String titleFor(RejectionCode code) {
        return switch (code) {
            case INSUFFICIENT_STOCK -> "Not enough stock";
            case UNKNOWN_SKU -> "Not stocked";
            case RESERVATION_NOT_FOUND -> "No such reservation";
            case RESERVATION_EXPIRED -> "Hold expired";
            case ALREADY_COMMITTED -> "Already paid";
            case ALREADY_RELEASED -> "Already released";
            case RESERVATION_ID_IN_USE -> "Reservation id in use";
            case IDEMPOTENCY_KEY_REUSED -> "Idempotency key reused";
        };
    }

}
