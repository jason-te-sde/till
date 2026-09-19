package io.till.catalogue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.till.core.Event;
import io.till.core.IdempotencyKey;
import io.till.core.Line;
import io.till.core.ReservationId;
import io.till.core.Sku;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The storefront's read endpoints, over HTTP, against a real database.
 *
 * <p>Reads only. Checkout forwards to till, and a test that faked till would be asserting that a
 * stub returns what the stub was told to return; the real path is exercised by the container stack
 * in CI, where both services and a broker are actually running.
 *
 * <p>What is worth checking here is the shape of the join between the two halves of this service —
 * a catalogue that changes when marketing edits a blurb, and a projection that changes thousands of
 * times an hour — and in particular what a game with no projection row reads as.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StoreApiTest {

    private static final Instant T0 = Instant.parse("2026-09-18T12:00:00Z");

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @LocalServerPort
    int port;

    @Autowired
    AvailabilityProjection projection;

    @Autowired
    DataSource dataSource;

    @Autowired
    JsonMapper json;

    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void clearProjection() throws SQLException {
        try (var connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("truncate catalogue_availability, catalogue_consumed_event");
        }
    }

    @Test
    @DisplayName("the catalogue lists what the migration seeded, in title order")
    void listsTheCatalogue() {
        Map<?, ?> body = get("/v1/games");

        List<?> items = (List<?>) body.get("items");
        assertEquals(8, items.size());
        List<String> titles = items.stream().map(i -> (String) ((Map<?, ?>) i).get("title")).toList();
        assertEquals(titles.stream().sorted().toList(), titles, "ordered by title");
        Map<?, ?> first = (Map<?, ?>) items.get(0);
        assertEquals("Deep Field", first.get("title"));
        assertEquals(4499, ((Number) first.get("priceCents")).longValue(), "minor units, never a float");
    }

    @Test
    @DisplayName("a game the projection has never heard of reads as sold out, not as unlimited")
    void unknownAvailabilityIsZero() {
        Map<?, ?> game = get("/v1/games/tessera");

        assertEquals(0, ((Number) game.get("available")).longValue());
        // And it says it does not know, rather than implying a fresh zero.
        assertNull(game.get("availabilityAsOf"), "no event has been applied, so there is no as-of");
    }

    @Test
    @DisplayName("availability comes from the projection, stamped with when till decided it")
    void availabilityComesFromTheProjection() {
        projection.apply("adjusted:d1", 1, adjusted("tessera", 10, 0));
        projection.apply(
                "reserved:r1",
                2,
                new Event.StockReserved(
                        ReservationId.of("r1"),
                        List.of(Line.of("tessera", 3)),
                        T0.plus(Duration.ofMinutes(15)),
                        T0));

        Map<?, ?> game = get("/v1/games/tessera");

        assertEquals(7, ((Number) game.get("available")).longValue());
        assertNotNull(game.get("availabilityAsOf"));
        assertTrue(game.get("availabilityAsOf").toString().startsWith("2026-09-18T12:00"), "till's clock, not ours");
    }

    @Test
    @DisplayName("the listing reads every level in one query, not one per game")
    void theListingDoesNotFanOut() {
        projection.apply("adjusted:d1", 1, adjusted("tessera", 5, 0));
        projection.apply("adjusted:d2", 2, adjusted("deep-field", 2, 1));

        Map<?, ?> body = get("/v1/games");

        // The behaviour, at least: every game carries the level it should, including the ones with
        // no row. The single-query shape is in AvailabilityProjection.of(List) — this is what would
        // break first if somebody replaced it with a loop that swallowed misses.
        Map<String, Long> available = ((List<?>) body.get("items"))
                .stream()
                        .map(item -> (Map<?, ?>) item)
                        .collect(
                                Collectors.toMap(
                                        item -> (String) item.get("sku"),
                                        item -> ((Number) item.get("available")).longValue()));
        assertEquals(5, available.get("tessera"));
        assertEquals(1, available.get("deep-field"));
        assertEquals(0, available.get("lantern-run"), "no row, so nothing on offer");
        assertEquals(8, available.size());
    }

    @Test
    @DisplayName("a SKU that is not in the catalogue is a 404, even if the ledger knows about it")
    void unknownGameIsNotFound() {
        // till's SKUs are opaque strings and it will happily hold stock of something this shop does
        // not sell. That is not this service's to display.
        projection.apply("adjusted:d1", 1, adjusted("some-other-product", 99, 0));

        assertEquals(404, send("/v1/games/some-other-product").statusCode());
    }

    @Test
    @DisplayName("the page size is clamped rather than refused")
    void limitIsClamped() {
        assertEquals(8, ((List<?>) get("/v1/games?limit=10000").get("items")).size());
        assertEquals(1, ((List<?>) get("/v1/games?limit=1").get("items")).size());
    }

    private Map<?, ?> get(String path) {
        HttpResponse<String> response = send(path);
        assertEquals(200, response.statusCode(), path + " -> " + response.body());
        return json.readValue(response.body(), Map.class);
    }

    private HttpResponse<String> send(String path) {
        try {
            return http.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException(path, e);
        }
    }

    private static Event.StockAdjusted adjusted(String sku, long onHand, long reserved) {
        return new Event.StockAdjusted(
                IdempotencyKey.of("k-" + sku), Sku.of(sku), onHand, onHand, reserved, T0);
    }
}
