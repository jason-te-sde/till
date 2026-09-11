package io.till.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.till.client.TillApiException;
import io.till.core.IdempotencyKey;
import io.till.core.Line;
import io.till.core.Sku;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

// JUnit does not inherit @ResourceLock from a superclass, so every class that shares the one
// database names the lock itself. Without it two Spring contexts write the same tables at once and
// each sees the other's rows.
@ResourceLock("till-database")
class AuthApiTest extends ApiTestBase {

    @Test
    @DisplayName("no token is a 401")
    void noToken() {
        TillApiException thrown = assertThrows(TillApiException.class, () -> client(null).stock(Sku.of("widget")));

        assertEquals(401, thrown.status());
    }

    @Test
    @DisplayName("a token that is not one of ours is a 401")
    void wrongToken() {
        assertEquals(
                401,
                assertThrows(TillApiException.class, () -> client("nope").stock(Sku.of("widget"))).status());
    }

    @Test
    @DisplayName("the client token cannot change stock levels")
    void clientTokenCannotAdjust() {
        TillApiException thrown =
                assertThrows(
                        TillApiException.class,
                        () -> client(CLIENT_TOKEN).adjust(IdempotencyKey.of("d1"), Sku.of("widget"), 10));

        assertEquals(403, thrown.status());
        assertTrue(thrown.getMessage().contains("admin"), thrown.getMessage());
    }

    @Test
    @DisplayName("the admin token can do everything the client token can")
    void adminTokenIsASuperset() {
        client(ADMIN_TOKEN).adjust(IdempotencyKey.of("d1"), Sku.of("widget"), 10);

        assertEquals(
                10,
                client(ADMIN_TOKEN)
                        .reserve(IdempotencyKey.of("c1"), List.of(Line.of("widget", 10)), null)
                        .lines()
                        .get(0)
                        .quantity());
    }

    @Test
    @DisplayName("a request that changes something without an Idempotency-Key is a 400 that says why")
    void idempotencyKeyIsRequired() throws IOException, InterruptedException {
        HttpResponse<String> response =
                HttpClient.newHttpClient()
                        .send(
                                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v1/reservations"))
                                        .header("Authorization", "Bearer " + CLIENT_TOKEN)
                                        .header("Content-Type", "application/json")
                                        .POST(
                                                HttpRequest.BodyPublishers.ofString(
                                                        "{\"lines\":[{\"sku\":\"widget\",\"quantity\":1}]}"))
                                        .build(),
                                HttpResponse.BodyHandlers.ofString());

        assertEquals(400, response.statusCode());
        assertTrue(response.body().contains("Idempotency-Key"), response.body());
        assertTrue(response.body().contains("retrying"), response.body());
    }

    @Test
    @DisplayName("health and metrics do not need a token, because they are on another port in production")
    void managementIsNotBehindTheApiToken() throws IOException, InterruptedException {
        HttpResponse<String> response =
                HttpClient.newHttpClient()
                        .send(
                                HttpRequest.newBuilder(
                                                URI.create("http://127.0.0.1:" + port + "/actuator/health"))
                                        .GET()
                                        .build(),
                                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode(), response.body());
        assertTrue(response.body().contains("UP"), response.body());
    }
}
