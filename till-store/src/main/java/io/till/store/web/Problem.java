package io.till.store.web;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * What every error from this API looks like, for the published contract.
 *
 * <p>Never instantiated: responses are Spring's {@code ProblemDetail}, built by {@link Problems} and by
 * the security layer's own handlers. This is the one declaration of their shape, so the contract can
 * say what an error is and the SPA's generated types have a name for it. Two declarations of one shape
 * drift, which is why {@code OpenApiContractTest} holds real failures up against this one.
 *
 * @param type a URI naming the kind of problem; absent means {@code about:blank}
 * @param title a short, fixed summary of the kind of problem
 * @param status the HTTP status, repeated
 * @param detail what happened this time, in a sentence
 * @param instance the path that failed, when the failure reached a controller
 * @param code what went wrong, for code to branch on
 * @param errors every invalid field, when {@code code} is {@code INVALID_BODY}
 * @param shortfalls how far short each game fell, when {@code code} is {@code INSUFFICIENT_STOCK}
 */
@Schema(name = "Problem", description = "An RFC 9457 problem, with a machine-readable code.")
public record Problem(
        @Schema(requiredMode = NOT_REQUIRED) String type,
        @Schema(requiredMode = REQUIRED) String title,
        @Schema(requiredMode = REQUIRED) int status,
        @Schema(requiredMode = REQUIRED) String detail,
        @Schema(requiredMode = NOT_REQUIRED) String instance,
        @Schema(
                        requiredMode = REQUIRED,
                        description = "NOT_FOUND, INSUFFICIENT_STOCK, UNKNOWN_SKU, ORDER_EXPIRED, ORDER_CANCELLED, "
                                + "ORDER_PAID, IDEMPOTENCY_KEY_REUSED, LEDGER_UNAVAILABLE, BAD_REQUEST, INVALID_BODY, "
                                + "MISSING_HEADER, UNAUTHORIZED, FORBIDDEN, CSRF, INTERNAL_ERROR; otherwise the name "
                                + "of the HTTP status")
                String code,
        @Schema(requiredMode = NOT_REQUIRED) List<FieldError> errors,
        @Schema(requiredMode = NOT_REQUIRED) List<Shortfall> shortfalls) {

    /**
     * One invalid field.
     *
     * @param field its path in the request body, such as {@code lines[0].quantity}
     * @param message what is wrong with it
     */
    @Schema(name = "ProblemFieldError")
    public record FieldError(String field, String message) {}

    /**
     * How far short one game fell.
     *
     * @param sku which game
     * @param requested copies asked for
     * @param available copies that could have been had
     */
    @Schema(name = "ProblemShortfall")
    public record Shortfall(String sku, long requested, long available) {}
}
