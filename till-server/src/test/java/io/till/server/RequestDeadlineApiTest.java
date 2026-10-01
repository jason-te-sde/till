package io.till.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micrometer.core.instrument.MeterRegistry;
import io.till.core.IdempotencyKey;
import io.till.core.Sku;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * {@code Till-Timeout-Ms}, over the wire: the caller's own budget bounds the ledger, not only the
 * client that sent it.
 *
 * <p>Raw HTTP rather than {@link io.till.client.TillClient}, because the values worth sending here
 * — {@code 0}, and a header the client would never construct — are exactly the ones its own builder
 * refuses. {@link CommandsTest} covers the arithmetic and {@code till.late}, which this cannot reach
 * deterministically against a real clock.
 */
// JUnit does not inherit @ResourceLock from a superclass, so every class that shares the one
// database names the lock itself. Without it two Spring contexts write the same tables at once and
// each sees the other's rows.
@ResourceLock("till-database")
class RequestDeadlineApiTest extends ApiTestBase {

    @Autowired
    MeterRegistry meters;

    /**
     * The registry is a singleton in a context that outlives any one test, so it is emptied here;
     * see the same note in {@link BackgroundJobsApiTest}.
     */
    @BeforeEach
    void forgetEarlierTraffic() {
        meters.clear();
    }

    @Test
    @DisplayName("Till-Timeout-Ms: 0 is answered 503 DEADLINE_EXCEEDED, with no reservation and no outbox row")
    void zeroBudgetIsRefused() throws IOException, InterruptedException {
        admin().adjust(IdempotencyKey.of("d1"), Sku.of("widget"), 10);
        int eventsBefore = ledger.allEvents().size();

        HttpResponse<String> response = reserve("c1", "0");

        assertEquals(503, response.statusCode(), response.body());
        assertTrue(response.body().contains("\"code\":\"DEADLINE_EXCEEDED\""), response.body());
        assertTrue(ledger.allReservations().isEmpty(), "nothing was applied");
        assertEquals(
                eventsBefore,
                ledger.allEvents().size(),
                "no outbox row for a reservation that was never applied");
        assertEquals(
                1,
                meters.counter("till.outcome", "kind", "reserve", "outcome", "deadline_exceeded").count(),
                1e-9);
    }

    @Test
    @DisplayName("the same reserve with a generous budget succeeds")
    void generousBudgetSucceeds() throws IOException, InterruptedException {
        admin().adjust(IdempotencyKey.of("d1"), Sku.of("widget"), 10);

        HttpResponse<String> response = reserve("c1", "60000");

        assertEquals(201, response.statusCode(), response.body());
        assertEquals(1, ledger.allReservations().size());
    }

    @Test
    @DisplayName("a malformed Till-Timeout-Ms is a 400, not a 500, and nothing runs")
    void malformedHeaderIsA400() throws IOException, InterruptedException {
        admin().adjust(IdempotencyKey.of("d1"), Sku.of("widget"), 10);

        HttpResponse<String> response = reserve("c1", "soon");

        assertEquals(400, response.statusCode(), response.body());
        assertTrue(response.body().contains("Till-Timeout-Ms"), response.body());
        assertTrue(ledger.allReservations().isEmpty());
    }

    private HttpResponse<String> reserve(String idempotencyKey, String timeoutHeader)
            throws IOException, InterruptedException {
        return HttpClient.newHttpClient()
                .send(
                        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v1/reservations"))
                                .header("Authorization", "Bearer " + CLIENT_TOKEN)
                                .header("Idempotency-Key", idempotencyKey)
                                .header("Content-Type", "application/json")
                                .header("Till-Timeout-Ms", timeoutHeader)
                                .POST(
                                        HttpRequest.BodyPublishers.ofString(
                                                "{\"lines\":[{\"sku\":\"widget\",\"quantity\":1}]}"))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
    }
}
