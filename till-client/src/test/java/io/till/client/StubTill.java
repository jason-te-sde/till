package io.till.client;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * A one-endpoint HTTP server for the client's tests.
 *
 * <p>The JDK's own server, so the client module keeps its "no dependencies" claim through its tests
 * as well. What it is for is the behaviour a mock cannot check: that a retry really does go over the
 * wire with the same {@code Idempotency-Key} header, and that a connection dropped mid-request is
 * retried at all.
 */
final class StubTill implements AutoCloseable {

    /** One request, as the server saw it. */
    record Seen(String method, String path, String idempotencyKey, String authorization, String body) {}

    private final HttpServer server;
    private final List<Seen> seen = new CopyOnWriteArrayList<>();
    private final List<Function<Seen, Reply>> script = new ArrayList<>();
    private Function<Seen, Reply> fallback = request -> new Reply(404, "{\"detail\":\"no stub for " + request.path() + "\"}");
    private int served;

    /** What the stub answers with. */
    record Reply(int status, String body) {}

    StubTill() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.createContext("/", this::handle);
        server.start();
    }

    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** Queues one reply, used for the next request and then discarded. */
    StubTill then(Function<Seen, Reply> reply) {
        script.add(reply);
        return this;
    }

    StubTill then(int status, String body) {
        return then(request -> new Reply(status, body));
    }

    /** Used once the queue is empty. */
    StubTill always(int status, String body) {
        this.fallback = request -> new Reply(status, body);
        return this;
    }

    List<Seen> requests() {
        return List.copyOf(seen);
    }

    private void handle(HttpExchange exchange) throws IOException {
        byte[] body = exchange.getRequestBody().readAllBytes();
        Seen request =
                new Seen(
                        exchange.getRequestMethod(),
                        exchange.getRequestURI().getPath(),
                        exchange.getRequestHeaders().getFirst("Idempotency-Key"),
                        exchange.getRequestHeaders().getFirst("Authorization"),
                        new String(body, StandardCharsets.UTF_8));
        seen.add(request);

        Function<Seen, Reply> responder = served < script.size() ? script.get(served) : fallback;
        served++;
        Reply reply = responder.apply(request);

        if (reply.status() == 0) {
            // Close without answering, which is what the client sees when a connection drops
            // between the request arriving and the answer being written.
            exchange.close();
            return;
        }
        byte[] payload = reply.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(reply.status(), payload.length);
        exchange.getResponseBody().write(payload);
        exchange.close();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
