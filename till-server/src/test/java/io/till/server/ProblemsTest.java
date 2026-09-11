package io.till.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.till.core.Outcome;
import io.till.core.RejectionCode;
import io.till.core.Sku;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

/** The status mapping, without a container anywhere near it. */
class ProblemsTest {

    @ParameterizedTest
    @EnumSource(RejectionCode.class)
    @DisplayName("every rejection code has a status, and none of them is a 500")
    void everyCodeMaps(RejectionCode code) {
        HttpStatus status = Problems.statusFor(code);

        assertEquals(
                true,
                status.is4xxClientError(),
                code + " mapped to " + status + "; a rejection is an answer, not a server failure");
    }

    @Test
    @DisplayName("an expired hold is 410 Gone, which says something 404 does not")
    void expiryIsGone() {
        assertEquals(HttpStatus.GONE, Problems.statusFor(RejectionCode.RESERVATION_EXPIRED));
    }

    @Test
    @DisplayName("a reused key is 422: the request was understood and cannot be answered")
    void keyReuseIsUnprocessable() {
        assertEquals(HttpStatus.UNPROCESSABLE_CONTENT, Problems.statusFor(RejectionCode.IDEMPOTENCY_KEY_REUSED));
    }

    @Test
    @DisplayName("the body carries the code and every shortfall")
    void bodyCarriesTheDetail() {
        Outcome.Rejected rejected =
                new Outcome.Rejected(
                        RejectionCode.INSUFFICIENT_STOCK,
                        "not enough stock for widget",
                        List.of(new Outcome.Shortfall(Sku.of("widget"), 5, 2)));

        ProblemDetail problem = Problems.of(rejected);

        assertEquals(409, problem.getStatus());
        assertEquals("Not enough stock", problem.getTitle());
        assertEquals("not enough stock for widget", problem.getDetail());
        assertEquals("INSUFFICIENT_STOCK", problem.getProperties().get("code"));
        assertEquals(
                List.of(new Api.Shortfall("widget", 5, 2)), problem.getProperties().get("shortfalls"));
    }

    @Test
    @DisplayName("a refusal with no per-SKU detail carries no shortfalls field at all")
    void noEmptyShortfalls() {
        ProblemDetail problem =
                Problems.of(Outcome.Rejected.of(RejectionCode.RESERVATION_NOT_FOUND, "no reservation r9"));

        assertEquals("RESERVATION_NOT_FOUND", problem.getProperties().get("code"));
        assertNull(problem.getProperties().get("shortfalls"), "an empty array would be noise in every 404");
    }
}
