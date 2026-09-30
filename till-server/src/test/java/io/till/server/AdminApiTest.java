package io.till.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.till.client.TillApiException;
import io.till.client.TillClient;
import io.till.core.IdempotencyKey;
import io.till.core.Line;
import io.till.core.ReservationState;
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

/** The read endpoints the operator console is built on. */
// Suites that share the one database run one at a time; see the note in the other API tests.
@ResourceLock("till-database")
class AdminApiTest extends ApiTestBase {

    @Test
    @DisplayName("stock lists in SKU order and hands back a cursor while there is more")
    void stockPages() {
        TillClient till = admin();
        for (String sku : List.of("aaa", "bbb", "ccc")) {
            till.adjust(IdempotencyKey.of("d-" + sku), Sku.of(sku), 5);
        }

        TillClient.StockPage first = till.listStock(2, null);
        assertEquals(List.of("aaa", "bbb"), first.items().stream().map(s -> s.sku().value()).toList());
        assertEquals("bbb", first.nextAfter());

        TillClient.StockPage second = till.listStock(2, first.nextAfter());
        assertEquals(List.of("ccc"), second.items().stream().map(s -> s.sku().value()).toList());
        assertNull(second.nextAfter(), "a short page is the end, and offering a cursor there wastes a call");
    }

    @Test
    @DisplayName("the admin token splits a SKU, which then reports its rows and goes on selling to the last unit")
    void splitting() {
        admin().adjust(IdempotencyKey.of("d1"), Sku.of("widget"), 20);
        client().reserve(IdempotencyKey.of("c1"), List.of(Line.of("widget", 3)), null);

        TillClient.StockView split = admin().shard(IdempotencyKey.of("s1"), Sku.of("widget"), 8);

        assertEquals(List.of(20L, 3L, 17L, 8), List.of(split.onHand(), split.reserved(), split.available(), split.shards()));
        assertEquals(8, client().stock(Sku.of("widget")).shards());
        assertEquals(8, admin().listStock(10, null).items().get(0).shards());
        assertEquals(8, admin().shard(IdempotencyKey.of("s2"), Sku.of("widget"), 2).shards(), "rows are only ever added");

        for (int i = 0; i < 17; i++) {
            client().reserve(IdempotencyKey.of("c-" + i), List.of(Line.of("widget", 1)), null);
        }
        assertEquals(0, client().stock(Sku.of("widget")).available(), "every unit, from whichever row");
        TillApiException refused = assertThrows(
                TillApiException.class,
                () -> client().reserve(IdempotencyKey.of("c-last"), List.of(Line.of("widget", 1)), null));
        assertEquals(409, refused.status());
    }

    @Test
    @DisplayName("splitting needs the admin token, a SKU that exists, and one to sixty-four rows")
    void splittingIsGuarded() {
        admin().adjust(IdempotencyKey.of("d1"), Sku.of("widget"), 5);

        assertEquals(403, assertThrows(TillApiException.class,
                () -> client().shard(IdempotencyKey.of("s1"), Sku.of("widget"), 4)).status());
        assertEquals(404, assertThrows(TillApiException.class,
                () -> admin().shard(IdempotencyKey.of("s2"), Sku.of("ghost"), 4)).status());
        assertEquals(400, assertThrows(TillApiException.class,
                () -> admin().shard(IdempotencyKey.of("s3"), Sku.of("widget"), 0)).status());
        assertEquals(400, assertThrows(TillApiException.class,
                () -> admin().shard(IdempotencyKey.of("s4"), Sku.of("widget"), 65)).status());
        assertEquals(1, admin().stock(Sku.of("widget")).shards(), "and none of that split anything");
    }

    @Test
    @DisplayName("reservations list newest first and filter by state")
    void reservationsList() {
        TillClient till = client();
        admin().adjust(IdempotencyKey.of("d1"), Sku.of("widget"), 100);

        var first = till.reserve(IdempotencyKey.of("c1"), List.of(Line.of("widget", 1)), null);
        clock.advance(java.time.Duration.ofSeconds(1));
        var second = till.reserve(IdempotencyKey.of("c2"), List.of(Line.of("widget", 1)), null);
        till.commit(IdempotencyKey.of("pay-1"), first.id());

        assertEquals(
                List.of(second.id(), first.id()),
                till.listReservations(null, 10).stream().map(TillClient.ReservationView::id).toList(),
                "newest first");
        assertEquals(
                List.of(first.id()),
                till.listReservations(ReservationState.COMMITTED, 10).stream()
                        .map(TillClient.ReservationView::id)
                        .toList());
        assertEquals(
                ReservationState.HELD, till.listReservations(ReservationState.HELD, 10).get(0).state());
    }

    @Test
    @DisplayName("a listed reservation reports both its stored state and what it effectively is")
    void listedReservationsReportBothStates() {
        admin().adjust(IdempotencyKey.of("d1"), Sku.of("widget"), 10);
        client().reserve(IdempotencyKey.of("c1"), List.of(Line.of("widget", 1)), java.time.Duration.ofMinutes(1));

        clock.advance(java.time.Duration.ofMinutes(2));

        TillClient.ReservationView listed = client().listReservations(null, 10).get(0);
        assertEquals(ReservationState.HELD, listed.state(), "nothing has swept, so the row still says held");
        assertEquals(ReservationState.EXPIRED, listed.effectiveState(), "and the deadline says otherwise");
    }

    @Test
    @DisplayName("the outbox needs the admin token, and reports the real backlog rather than the page")
    void outboxIsAdminOnly() {
        admin().adjust(IdempotencyKey.of("d1"), Sku.of("widget"), 10);
        client().reserve(IdempotencyKey.of("c1"), List.of(Line.of("widget", 1)), null);

        assertEquals(403, assertThrows(TillApiException.class, () -> client().outbox(10)).status());

        TillClient.OutboxPage page = admin().outbox(1);
        assertEquals(2, page.backlog(), "two events written, one asked for");
        assertEquals(1, page.items().size());
        assertEquals("adjusted:d1", page.items().get(0).dedupeKey());
    }

    @Test
    @DisplayName("a nonsense limit is a 400, not a five hundred thousand row answer")
    void limitsAreValidated() {
        assertEquals(400, assertThrows(TillApiException.class, () -> admin().outbox(0)).status());
        assertEquals(400, assertThrows(TillApiException.class, () -> admin().listStock(0, null)).status());
    }

    @Test
    @DisplayName("a mistyped API path is a 404 problem, not an HTML page")
    void theSpaForwardDoesNotSwallowTheApi() throws IOException, InterruptedException {
        HttpResponse<String> response = get("/v1/nonsense");

        assertEquals(404, response.statusCode());
        assertTrue(
                response.body() == null || !response.body().contains("<html"),
                "an HTML error page is what a client would then try to parse as JSON: "
                        + response.body());
    }

    @Test
    @DisplayName("the ledger serves an API and nothing else — no pages, however the path is spelled")
    void noPages() throws IOException, InterruptedException {
        // The storefront and the operator console are the store's, served by the edge proxy. A page
        // here would be a second way into the ledger that the store's access rules do not cover.
        assertEquals(404, get("/").statusCode());
        assertEquals(404, get("/ops/reservations").statusCode());
        assertEquals(404, get("/nonsense").statusCode());
    }

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        return HttpClient.newHttpClient()
                .send(
                        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                                .header("Authorization", "Bearer " + CLIENT_TOKEN)
                                .GET()
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
    }
}
