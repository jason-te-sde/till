package io.till.server;

import io.till.core.Outcome;
import io.till.core.RejectionCode;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Turns a rejection into an HTTP answer.
 *
 * <p>The mapping lives here rather than on {@link RejectionCode} so that a caller embedding the
 * kernel in something that is not a web service does not inherit a set of status codes it has no use
 * for.
 *
 * <p>The choices worth defending:
 *
 * <ul>
 *   <li><b>410 Gone</b> for an expired hold, not 404 and not 409. The reservation existed, the caller
 *       had it, and it is no longer available — which is what 410 means and what 404 does not.
 *   <li><b>422</b> for a reused idempotency key. The request is well formed and the server understood
 *       it; what it cannot do is decide which of two different requests the key names.
 *   <li><b>503 with Retry-After</b> for exhausted attempts, because the command is fine and will very
 *       likely succeed in a moment. A 500 there would page somebody about contention.
 * </ul>
 *
 * <p>Bodies are RFC 9457 problem details with three extensions:
 *
 * <ul>
 *   <li>{@code code} — the {@link RejectionCode}, so a client can branch on something stable rather
 *       than on prose.
 *   <li>{@code shortfalls} — so a client refused for stock can offer a smaller basket without
 *       another round trip.
 *   <li>{@code requestId} — so a screenshot of an error is enough to find the log lines.
 * </ul>
 */
final class Problems {

    private Problems() {}

    /**
     * The problem body for a rejection.
     *
     * @param rejected what the kernel refused and why
     * @return the body, with its status already set
     */
    static ProblemDetail of(Outcome.Rejected rejected) {
        ProblemDetail problem = of(statusFor(rejected.code()), title(rejected.code()), rejected.detail());
        problem.setProperty("code", rejected.code().name());
        if (!rejected.shortfalls().isEmpty()) {
            problem.setProperty(
                    "shortfalls",
                    rejected.shortfalls().stream()
                            .map(s -> new Api.Shortfall(s.sku().value(), s.requested(), s.available()))
                            .toList());
        }
        return problem;
    }

    /**
     * A problem with no rejection behind it.
     *
     * @param status what to answer
     * @param title a short name for the class of failure
     * @param detail the sentence naming the specific thing
     * @return the body
     */
    static ProblemDetail of(HttpStatus status, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        withRequestId(problem);
        return problem;
    }

    /**
     * Attaches the request's identifier, if there is one.
     *
     * <p>So that a screenshot of an error is enough to find the log lines. Read from the request
     * attribute rather than the MDC because an exception handler may run on a different thread from
     * the one that set it.
     */
    private static void withRequestId(ProblemDetail problem) {
        if (RequestContextHolder.getRequestAttributes()
                instanceof ServletRequestAttributes attributes) {
            String id = RequestId.of(attributes.getRequest());
            if (id != null) {
                problem.setProperty("requestId", id);
            }
        }
    }

    /**
     * The status a rejection should be answered with.
     *
     * @param code why the command was refused
     * @return the status
     */
    static HttpStatus statusFor(RejectionCode code) {
        return switch (code) {
            case INSUFFICIENT_STOCK, ALREADY_COMMITTED, ALREADY_RELEASED, RESERVATION_ID_IN_USE -> HttpStatus.CONFLICT;
            case UNKNOWN_SKU, RESERVATION_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case RESERVATION_EXPIRED -> HttpStatus.GONE;
            case IDEMPOTENCY_KEY_REUSED -> HttpStatus.UNPROCESSABLE_CONTENT;
        };
    }

    private static String title(RejectionCode code) {
        return switch (code) {
            case INSUFFICIENT_STOCK -> "Not enough stock";
            case UNKNOWN_SKU -> "Unknown SKU";
            case RESERVATION_NOT_FOUND -> "No such reservation";
            case RESERVATION_EXPIRED -> "Reservation expired";
            case ALREADY_COMMITTED -> "Already committed";
            case ALREADY_RELEASED -> "Already released";
            case RESERVATION_ID_IN_USE -> "Reservation id in use";
            case IDEMPOTENCY_KEY_REUSED -> "Idempotency key reused";
        };
    }
}
