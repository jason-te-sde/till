package io.till.client;

import io.till.core.RejectionCode;
import java.util.List;
import java.util.Optional;

/**
 * The service answered, and the answer was no.
 *
 * <p>Carries the {@code code} extension from the problem body rather than only the status, because a
 * caller that has to distinguish "out of stock" from "this SKU does not exist" would otherwise be
 * parsing prose or guessing from a 404.
 */
public class TillApiException extends RuntimeException {

    private final int status;
    private final String code;
    private final String detail;
    private final List<Shortfall> shortfalls;

    /**
     * @param status the HTTP status
     * @param code the {@code code} extension, or null if the body had none
     * @param detail the {@code detail} field
     * @param shortfalls the {@code shortfalls} extension, empty if absent
     */
    public TillApiException(int status, String code, String detail, List<Shortfall> shortfalls) {
        super(status + " " + (code == null ? "" : code + ": ") + detail);
        this.status = status;
        this.code = code;
        this.detail = detail;
        this.shortfalls = List.copyOf(shortfalls);
    }

    /**
     * The service's own sentence about what went wrong, without the status and code in front of it.
     *
     * <p>{@link #getMessage()} carries all three, which is right for a log line and wrong for a caller
     * passing the refusal on to somebody else — a storefront showing a customer why their basket was
     * refused wants the sentence, not "409 INSUFFICIENT_STOCK:" in front of it.
     *
     * @return the {@code detail} field from the problem body
     */
    public String detail() {
        return detail;
    }

    /**
     * The HTTP status.
     *
     * @return the status
     */
    public int status() {
        return status;
    }

    /**
     * The rejection code, when the service refused a command rather than the request.
     *
     * @return the code, or empty for a problem that is not a rejection
     */
    public Optional<RejectionCode> rejection() {
        if (code == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(RejectionCode.valueOf(code));
        } catch (IllegalArgumentException e) {
            // A code this build of the client does not know: a newer service. Reporting it as absent
            // is right, because the caller cannot branch on something it has no constant for.
            return Optional.empty();
        }
    }

    /**
     * Per-SKU detail for a refusal about stock.
     *
     * @return the shortfalls, empty for any other refusal
     */
    public List<Shortfall> shortfalls() {
        return shortfalls;
    }

    /**
     * How far short one SKU fell.
     *
     * @param sku which SKU
     * @param requested units asked for
     * @param available units that could have been taken
     */
    public record Shortfall(String sku, long requested, long available) {}
}
