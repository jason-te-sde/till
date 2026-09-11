package io.till.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.till.core.IdempotencyKey;
import io.till.core.Line;
import io.till.core.Outcome;
import io.till.core.RejectionCode;
import io.till.core.ReservationId;
import io.till.core.Sku;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TillClientTest {

    private static final IdempotencyKey KEY = IdempotencyKey.of("checkout-8123");

    private static final String RESERVED =
            """
            {"id":"r-1","lines":[{"sku":"widget","quantity":2}],"expiresAt":"2026-09-10T12:15:00Z"}""";

    @Test
    @DisplayName("a hold comes back as the kernel's own type")
    void reserve() {
        try (StubTill stub = new StubTill().always(201, RESERVED)) {
            TillClient client = TillClient.builder(stub.url()).token("secret").build();

            Outcome.Reserved reserved =
                    client.reserve(KEY, List.of(Line.of("widget", 2)), Duration.ofMinutes(15));

            assertEquals(ReservationId.of("r-1"), reserved.id());
            assertEquals(Instant.parse("2026-09-10T12:15:00Z"), reserved.expiresAt());
            assertEquals(List.of(Line.of("widget", 2)), reserved.lines());

            StubTill.Seen request = stub.requests().get(0);
            assertEquals("POST", request.method());
            assertEquals("/v1/reservations", request.path());
            assertEquals("checkout-8123", request.idempotencyKey());
            assertEquals("Bearer secret", request.authorization());
            assertTrue(request.body().contains("\"ttlSeconds\":900"), request.body());
        }
    }

    @Test
    @DisplayName("a 503 is retried with the same key, which is what makes retrying safe")
    void retriesContentionWithTheSameKey() {
        try (StubTill stub =
                new StubTill()
                        .then(503, "{\"code\":\"CONTENTION\",\"detail\":\"try again\"}")
                        .then(503, "{\"code\":\"CONTENTION\",\"detail\":\"try again\"}")
                        .always(201, RESERVED)) {
            TillClient client =
                    TillClient.builder(stub.url()).backoff(Duration.ofMillis(1)).maxAttempts(4).build();

            Outcome.Reserved reserved = client.reserve(KEY, List.of(Line.of("widget", 2)), null);

            assertEquals(ReservationId.of("r-1"), reserved.id());
            assertEquals(3, stub.requests().size());
            assertEquals(
                    List.of("checkout-8123", "checkout-8123", "checkout-8123"),
                    stub.requests().stream().map(StubTill.Seen::idempotencyKey).toList(),
                    "every attempt must carry the original key, or the retry becomes a second order");
        }
    }

    @Test
    @DisplayName("a dropped connection is retried, and with the same key")
    void retriesADroppedConnection() {
        try (StubTill stub = new StubTill().then(0, "").always(201, RESERVED)) {
            TillClient client = TillClient.builder(stub.url()).backoff(Duration.ofMillis(1)).build();

            // The hard case: the request may have arrived and been applied, and only the answer was
            // lost. The key is the only thing that can tell that apart from a request that never
            // landed.
            Outcome.Reserved reserved = client.reserve(KEY, List.of(Line.of("widget", 2)), null);

            assertEquals(ReservationId.of("r-1"), reserved.id());
            assertEquals(2, stub.requests().size());
            assertEquals("checkout-8123", stub.requests().get(1).idempotencyKey());
        }
    }

    @Test
    @DisplayName("contention that never clears reports what the service said, not an I/O failure")
    void givesUpOnContention() {
        try (StubTill stub = new StubTill().always(503, "{\"detail\":\"try again\"}")) {
            TillClient client =
                    TillClient.builder(stub.url()).backoff(Duration.ofMillis(1)).maxAttempts(3).build();

            TillApiException thrown =
                    assertThrows(
                            TillApiException.class,
                            () -> client.reserve(KEY, List.of(Line.of("widget", 2)), null));

            // The service answered every time, so the caller is told what it answered. An
            // UncheckedIOException here would claim the request never landed, which is a different
            // situation and a worse thing to report.
            assertEquals(503, thrown.status());
            assertEquals(3, stub.requests().size());
        }
    }

    @Test
    @DisplayName("a connection that never comes back reports an I/O failure")
    void givesUpOnAnUnreachableService() {
        try (StubTill stub = new StubTill().always(0, "")) {
            TillClient client =
                    TillClient.builder(stub.url()).backoff(Duration.ofMillis(1)).maxAttempts(2).build();

            assertThrows(
                    UncheckedIOException.class, () -> client.reserve(KEY, List.of(Line.of("widget", 2)), null));
            assertEquals(2, stub.requests().size());
        }
    }

    @Test
    @DisplayName("a refusal carries the code and every shortfall")
    void rejection() {
        String body =
                """
                {"title":"Not enough stock","status":409,"detail":"not enough stock for widget",
                 "code":"INSUFFICIENT_STOCK",
                 "shortfalls":[{"sku":"widget","requested":5,"available":2},
                               {"sku":"gadget","requested":1,"available":0}]}""";
        try (StubTill stub = new StubTill().always(409, body)) {
            TillClient client = TillClient.builder(stub.url()).build();

            TillApiException thrown =
                    assertThrows(
                            TillApiException.class,
                            () -> client.reserve(KEY, List.of(Line.of("widget", 5)), null));

            assertEquals(409, thrown.status());
            assertEquals(Optional.of(RejectionCode.INSUFFICIENT_STOCK), thrown.rejection());
            assertEquals(2, thrown.shortfalls().size());
            assertEquals(
                    new TillApiException.Shortfall("gadget", 1, 0), thrown.shortfalls().get(1));
        }
    }

    @Test
    @DisplayName("a 409 is not retried, because the answer will not change")
    void doesNotRetryARefusal() {
        try (StubTill stub = new StubTill().always(409, "{\"code\":\"ALREADY_COMMITTED\",\"detail\":\"no\"}")) {
            TillClient client = TillClient.builder(stub.url()).backoff(Duration.ofMillis(1)).build();

            assertThrows(TillApiException.class, () -> client.commit(KEY, ReservationId.of("r-1")));
            assertEquals(1, stub.requests().size());
        }
    }

    @Test
    @DisplayName("an error body that is not a problem detail still reports its status")
    void survivesAnUnparseableErrorBody() {
        try (StubTill stub = new StubTill().always(502, "<html>bad gateway</html>")) {
            TillClient client = TillClient.builder(stub.url()).build();

            TillApiException thrown = assertThrows(TillApiException.class, () -> client.stock(Sku.of("widget")));

            assertEquals(502, thrown.status());
            assertEquals(Optional.empty(), thrown.rejection());
        }
    }

    @Test
    @DisplayName("a code from a newer service is reported as unknown rather than crashing")
    void unknownRejectionCode() {
        try (StubTill stub = new StubTill().always(409, "{\"code\":\"SOMETHING_NEW\",\"detail\":\"?\"}")) {
            TillClient client = TillClient.builder(stub.url()).build();

            TillApiException thrown = assertThrows(TillApiException.class, () -> client.stock(Sku.of("widget")));

            assertEquals(Optional.empty(), thrown.rejection());
            assertTrue(thrown.getMessage().contains("SOMETHING_NEW"), thrown.getMessage());
        }
    }

    @Test
    @DisplayName("stock and reservation reads")
    void reads() {
        try (StubTill stub =
                new StubTill()
                        .then(200, "{\"sku\":\"widget\",\"onHand\":100,\"reserved\":2,\"available\":98}")
                        .then(
                                200,
                                """
                                {"id":"r-1","state":"HELD","effectiveState":"EXPIRED",
                                 "lines":[{"sku":"widget","quantity":2}],
                                 "createdAt":"2026-09-10T12:00:00Z","expiresAt":"2026-09-10T12:15:00Z"}""")) {
            TillClient client = TillClient.builder(stub.url()).build();

            TillClient.StockView stock = client.stock(Sku.of("widget"));
            assertEquals(100, stock.onHand());
            assertEquals(98, stock.available());

            TillClient.ReservationView view = client.reservation(ReservationId.of("r-1"));
            assertEquals(io.till.core.ReservationState.HELD, view.state());
            assertEquals(
                    io.till.core.ReservationState.EXPIRED,
                    view.effectiveState(),
                    "the stored state and the effective state are both reported, because they differ");
        }
    }

    @Test
    @DisplayName("a service with no token needs no Authorization header")
    void noToken() {
        try (StubTill stub = new StubTill().always(200, "{\"sku\":\"widget\",\"onHand\":1,\"reserved\":0,\"available\":1}")) {
            TillClient.builder(stub.url()).build().stock(Sku.of("widget"));

            assertEquals(null, stub.requests().get(0).authorization());
        }
    }

    @Test
    @DisplayName("a bad configuration is refused when the client is built")
    void configuration() {
        assertThrows(IllegalArgumentException.class, () -> TillClient.builder(""));
        assertThrows(IllegalArgumentException.class, () -> TillClient.builder("http://x").maxAttempts(0));
    }
}
