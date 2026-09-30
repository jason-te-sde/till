package io.till.client;

import io.till.core.IdempotencyKey;
import io.till.core.Line;
import io.till.core.Outcome;
import io.till.core.ReservationId;
import io.till.core.ReservationState;
import io.till.core.Sku;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A client for the till HTTP API.
 *
 * <pre>{@code
 * TillClient till = TillClient.builder("http://localhost:8080").token(token).build();
 * Outcome.Reserved held = till.reserve(
 *         IdempotencyKey.of("checkout-8123"), List.of(Line.of("widget", 2)), Duration.ofMinutes(15));
 * till.commit(IdempotencyKey.of("pay-8123"), held.id());
 * }</pre>
 *
 * <p><b>Retries are the point.</b> A 503 means the service gave up against contention and the command
 * is fine, so this retries it with backoff — carrying the <i>same idempotency key</i>, which is what
 * makes retrying a request that may already have been executed safe rather than a second order. A
 * connection that drops is retried the same way, and that is the harder case: the request may have
 * arrived, been applied, and had its answer lost, and the key is the only thing that can tell the
 * difference.
 *
 * <p>Backoff lives here rather than in the service, because a service that slept would be holding a
 * thread and a connection while it did.
 *
 * <p><b>A deadline bounds the whole call.</b> Without one, a service that has stopped answering costs
 * every attempt its full timeout, and the backoffs between them: four attempts at five seconds is a
 * customer waiting twenty, which is what the fourth load test's slowest checkouts were
 * (docs/load-test.md). With one, each attempt is given only what is left of it, a backoff that would
 * outlast it is not waited, and the call gives up when it runs out. So a quick 503 is still retried,
 * and a slow answer is not waited for twice.
 *
 * <p>When the attempts run out, what is thrown depends on what happened. A 503 on the last attempt
 * becomes a {@link TillApiException} carrying that status, because the service answered and the
 * caller should be told what it said. Only a request that never got an answer at all becomes an
 * {@link UncheckedIOException}, which is the case where the caller genuinely does not know whether
 * the command ran.
 *
 * <p>Instances are immutable and safe to share between threads.
 */
public final class TillClient {

    private static final Logger LOG = LoggerFactory.getLogger(TillClient.class);

    /** Attempts before a call gives up, unless configured otherwise. */
    public static final int DEFAULT_MAX_ATTEMPTS = 4;

    private final HttpClient http;
    private final URI base;
    private final String token;
    private final Duration timeout;
    private final Duration deadline;
    private final int maxAttempts;
    private final Duration backoff;
    private final Random jitter;

    private TillClient(Builder builder) {
        this.http =
                HttpClient.newBuilder()
                        .connectTimeout(builder.timeout)
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build();
        this.base = builder.base;
        this.token = builder.token;
        this.timeout = builder.timeout;
        this.deadline = builder.deadline;
        this.maxAttempts = builder.maxAttempts;
        this.backoff = builder.backoff;
        this.jitter = new Random();
    }

    /**
     * Starts configuring a client.
     *
     * @param baseUrl where the service is, for example {@code http://localhost:8080}
     * @return a builder
     */
    public static Builder builder(String baseUrl) {
        return new Builder(baseUrl);
    }

    /**
     * Takes a hold on stock.
     *
     * @param key the caller's key for this attempt; reuse it to retry safely
     * @param lines what to hold
     * @param ttl how long the hold lasts, or null for the service default
     * @return the hold
     * @throws TillApiException if the service refused
     */
    public Outcome.Reserved reserve(IdempotencyKey key, List<Line> lines, Duration ttl) {
        Map<String, Object> body = new LinkedHashMap<>();
        List<Object> encoded = new ArrayList<>();
        for (Line line : lines) {
            encoded.add(Map.of("sku", line.sku().value(), "quantity", line.quantity()));
        }
        body.put("lines", encoded);
        if (ttl != null) {
            body.put("ttlSeconds", ttl.toSeconds());
        }

        Map<String, Object> response = post("/v1/reservations", key, Json.write(body));
        return new Outcome.Reserved(
                ReservationId.of(string(response, "id")),
                readLines(response, "lines"),
                Instant.parse(string(response, "expiresAt")));
    }

    /**
     * Turns a hold into a sale.
     *
     * @param key the caller's key for this attempt
     * @param id which hold
     * @return the commit
     * @throws TillApiException if the service refused
     */
    public Outcome.Committed commit(IdempotencyKey key, ReservationId id) {
        Map<String, Object> response = post("/v1/reservations/" + id.value() + "/commit", key, "");
        return new Outcome.Committed(
                ReservationId.of(string(response, "id")), Instant.parse(string(response, "committedAt")));
    }

    /**
     * Gives a hold back.
     *
     * @param key the caller's key for this attempt
     * @param id which hold
     * @return the release
     * @throws TillApiException if the service refused
     */
    public Outcome.Released release(IdempotencyKey key, ReservationId id) {
        Map<String, Object> response = post("/v1/reservations/" + id.value() + "/release", key, "");
        return new Outcome.Released(
                ReservationId.of(string(response, "id")), Instant.parse(string(response, "releasedAt")));
    }

    /**
     * Changes on-hand stock. Needs the admin token.
     *
     * @param key the caller's key for this attempt
     * @param sku which SKU
     * @param delta units to add, negative to remove
     * @return the new level
     * @throws TillApiException if the service refused
     */
    public StockView adjust(IdempotencyKey key, Sku sku, long delta) {
        Map<String, Object> response =
                post("/v1/stock/" + sku.value() + "/adjust", key, Json.write(Map.of("delta", delta)));
        return StockView.of(response);
    }

    /**
     * Reads a stock level.
     *
     * @param sku which SKU
     * @return the level
     * @throws TillApiException if the SKU has never been stocked
     */
    public StockView stock(Sku sku) {
        return StockView.of(get("/v1/stock/" + sku.value()));
    }

    /**
     * Looks a hold up.
     *
     * @param id which hold
     * @return its state
     * @throws TillApiException if there is no such reservation
     */
    public ReservationView reservation(ReservationId id) {
        return ReservationView.of(get("/v1/reservations/" + id.value()));
    }

    /**
     * Lists stock levels, a page at a time.
     *
     * @param limit how many at most; the service clamps this
     * @param after the cursor from the previous page, or null to start
     * @return the page and a cursor for the next one
     */
    public StockPage listStock(int limit, String after) {
        String query = "?limit=" + limit + (after == null ? "" : "&after=" + encode(after));
        return StockPage.of(get("/v1/stock" + query));
    }

    /**
     * Lists reservations, newest first.
     *
     * @param state only this state, or null for all
     * @param limit how many at most; the service clamps this
     * @return the reservations
     */
    public List<ReservationView> listReservations(ReservationState state, int limit) {
        String query = "?limit=" + limit + (state == null ? "" : "&state=" + state.name());
        Map<String, Object> response = get("/v1/reservations" + query);
        return rows(response).stream().map(ReservationView::of).toList();
    }

    /**
     * The unpublished tail of the outbox. Needs the admin token.
     *
     * @param limit how many entries to return
     * @return the backlog and a page of it
     * @throws TillApiException with status 403 if the token is not the admin one
     */
    public OutboxPage outbox(int limit) {
        Map<String, Object> response = get("/v1/outbox?limit=" + limit);
        return new OutboxPage(
                number(response, "backlog"), rows(response).stream().map(OutboxItem::of).toList());
    }

    /**
     * A page of stock levels.
     *
     * @param items the levels, in SKU order
     * @param nextAfter pass back as {@code after} for the next page, or null at the end
     */
    public record StockPage(List<StockView> items, String nextAfter) {

        static StockPage of(Map<String, Object> json) {
            Object cursor = json.get("nextAfter");
            return new StockPage(
                    rows(json).stream().map(StockView::of).toList(),
                    cursor == null ? null : String.valueOf(cursor));
        }
    }

    /**
     * One event waiting to be published.
     *
     * @param sequence the ledger's ordering
     * @param dedupeKey what a consumer deduplicates on
     * @param recordedAt when the row was written
     * @param payload the event in its stored text form
     */
    public record OutboxItem(long sequence, String dedupeKey, Instant recordedAt, String payload) {

        static OutboxItem of(Map<String, Object> json) {
            return new OutboxItem(
                    number(json, "sequence"),
                    string(json, "dedupeKey"),
                    Instant.parse(string(json, "recordedAt")),
                    string(json, "payload"));
        }
    }

    /**
     * The unpublished tail of the outbox.
     *
     * @param backlog how many are waiting altogether, which is the number to alert on
     * @param items the oldest of them
     */
    public record OutboxPage(long backlog, List<OutboxItem> items) {}

    /**
     * A stock level, as the API reports it.
     *
     * @param sku the SKU
     * @param onHand units physically held
     * @param reserved units spoken for
     * @param available what a new hold may take
     */
    public record StockView(Sku sku, long onHand, long reserved, long available) {

        static StockView of(Map<String, Object> json) {
            return new StockView(
                    Sku.of(string(json, "sku")),
                    number(json, "onHand"),
                    number(json, "reserved"),
                    number(json, "available"));
        }
    }

    /**
     * One reservation, as the API reports it.
     *
     * @param id the hold
     * @param state the stored state
     * @param effectiveState what it is now: a held hold past its deadline reads as EXPIRED
     * @param lines what it holds
     * @param createdAt when it was taken
     * @param expiresAt when it stops counting
     */
    public record ReservationView(
            ReservationId id,
            ReservationState state,
            ReservationState effectiveState,
            List<Line> lines,
            Instant createdAt,
            Instant expiresAt) {

        static ReservationView of(Map<String, Object> json) {
            return new ReservationView(
                    ReservationId.of(string(json, "id")),
                    ReservationState.valueOf(string(json, "state")),
                    ReservationState.valueOf(string(json, "effectiveState")),
                    readLines(json, "lines"),
                    Instant.parse(string(json, "createdAt")),
                    Instant.parse(string(json, "expiresAt")));
        }
    }

    private Map<String, Object> post(String path, IdempotencyKey key, String body) {
        URI uri = base.resolve(path);
        HttpRequest.Builder request =
                HttpRequest.newBuilder(uri)
                        .header("Idempotency-Key", key.value())
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body));
        return send(uri, request);
    }

    private Map<String, Object> get(String path) {
        URI uri = base.resolve(path);
        return send(uri, HttpRequest.newBuilder(uri).GET());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> send(URI uri, HttpRequest.Builder builder) {
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        long started = System.nanoTime();

        // What the last attempt got: an answer telling it to retry, or no answer at all.
        HttpResponse<String> toldToRetry = null;
        IOException lastFailure = null;
        int attempt = 0;
        while (attempt < maxAttempts) {
            if (attempt > 0) {
                Duration pause = pause(attempt + 1);
                if (deadline != null && pause.compareTo(left(started)) >= 0) {
                    // Waiting would use up the rest of the deadline, and the caller is better off
                    // hearing now what the last attempt was told.
                    break;
                }
                sleep(pause);
            }
            Duration budget = timeout;
            if (deadline != null) {
                Duration left = left(started);
                if (left.isZero()) {
                    break;
                }
                if (left.compareTo(budget) < 0) {
                    budget = left;
                }
            }
            attempt++;
            HttpRequest request = builder.timeout(budget).build();
            HttpResponse<String> response;
            try {
                response = http.send(request, HttpResponse.BodyHandlers.ofString());
            } catch (IOException e) {
                // The request may have arrived, been applied, and had its answer lost. Retrying is
                // safe only because the idempotency key goes with it.
                LOG.debug("attempt {} of {} to {} failed", attempt, maxAttempts, uri, e);
                toldToRetry = null;
                lastFailure = e;
                continue;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted calling " + uri, e);
            }

            if (response.statusCode() == 503) {
                LOG.debug("attempt {} of {} to {} was told to retry", attempt, maxAttempts, uri);
                toldToRetry = response;
                lastFailure = null;
                continue;
            }
            if (response.statusCode() >= 400) {
                throw problem(response);
            }
            if (response.body() == null || response.body().isBlank()) {
                return Map.of();
            }
            Object parsed = Json.parse(response.body());
            if (!(parsed instanceof Map)) {
                throw new IllegalStateException("expected a JSON object from " + uri + ", got: " + parsed);
            }
            return (Map<String, Object>) parsed;
        }
        if (toldToRetry != null) {
            throw problem(toldToRetry);
        }
        String within = deadline == null ? "" : " in " + Duration.ofNanos(System.nanoTime() - started).toMillis() + " ms";
        throw new UncheckedIOException(
                "gave up calling " + uri + " after " + attempt + (attempt == 1 ? " attempt" : " attempts") + within,
                lastFailure != null ? lastFailure : new IOException("the deadline passed before an attempt could be made"));
    }

    /** What is left of the deadline; never negative. */
    private Duration left(long started) {
        Duration left = deadline.minusNanos(System.nanoTime() - started);
        return left.isNegative() ? Duration.ZERO : left;
    }

    @SuppressWarnings("unchecked")
    private static TillApiException problem(HttpResponse<String> response) {
        String code = null;
        String detail = response.body();
        List<TillApiException.Shortfall> shortfalls = new ArrayList<>();
        try {
            if (Json.parse(response.body()) instanceof Map<?, ?> body) {
                Object codeValue = body.get("code");
                code = codeValue == null ? null : String.valueOf(codeValue);
                Object detailValue = body.get("detail");
                detail = detailValue == null ? response.body() : String.valueOf(detailValue);
                if (body.get("shortfalls") instanceof List<?> list) {
                    for (Object entry : list) {
                        Map<String, Object> shortfall = (Map<String, Object>) entry;
                        shortfalls.add(
                                new TillApiException.Shortfall(
                                        string(shortfall, "sku"),
                                        number(shortfall, "requested"),
                                        number(shortfall, "available")));
                    }
                }
            }
        } catch (RuntimeException e) {
            // A body that is not a problem detail: a proxy's error page, most likely. The status is
            // still the useful part, and hiding it behind a parse failure would not help anybody.
            LOG.debug("could not read the error body from {}", response.uri(), e);
        }
        return new TillApiException(response.statusCode(), code, detail, shortfalls);
    }

    /** The wait before an attempt: the backoff, doubled for each attempt after the second, with jitter. */
    private Duration pause(int attempt) {
        long millis = backoff.toMillis() * (1L << (attempt - 2));
        return Duration.ofMillis(millis / 2 + jitter.nextLong(Math.max(1, millis)));
    }

    private static void sleep(Duration pause) {
        try {
            Thread.sleep(pause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while backing off", e);
        }
    }

    /**
     * The {@code items} array of a paged response.
     *
     * <p>Every listing wraps its rows in an object rather than returning a bare array, because a
     * bare array leaves nowhere to put a cursor without a breaking change.
     */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(Map<String, Object> json) {
        Object value = json.get("items");
        if (!(value instanceof List<?> list)) {
            throw new IllegalStateException("the response has no 'items' array: " + json);
        }
        return list.stream().map(entry -> (Map<String, Object>) entry).toList();
    }

    private static String encode(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String string(Map<String, Object> json, String field) {
        Object value = json.get(field);
        if (value == null) {
            throw new IllegalStateException("the response has no '" + field + "': " + json);
        }
        return String.valueOf(value);
    }

    private static long number(Map<String, Object> json, String field) {
        Object value = json.get(field);
        if (!(value instanceof Number n)) {
            throw new IllegalStateException("'" + field + "' is not a number in: " + json);
        }
        return n.longValue();
    }

    @SuppressWarnings("unchecked")
    private static List<Line> readLines(Map<String, Object> json, String field) {
        Object value = json.get(field);
        if (!(value instanceof List<?> list)) {
            throw new IllegalStateException("'" + field + "' is not an array in: " + json);
        }
        List<Line> lines = new ArrayList<>(list.size());
        for (Object entry : list) {
            Map<String, Object> line = (Map<String, Object>) entry;
            lines.add(new Line(Sku.of(string(line, "sku")), number(line, "quantity")));
        }
        return lines;
    }

    /** Configures a client. */
    public static final class Builder {

        private final URI base;
        private String token;
        private Duration timeout = Duration.ofSeconds(10);
        private Duration deadline;
        private int maxAttempts = DEFAULT_MAX_ATTEMPTS;
        private Duration backoff = Duration.ofMillis(100);

        private Builder(String baseUrl) {
            if (baseUrl == null || baseUrl.isBlank()) {
                throw new IllegalArgumentException("a base URL is required");
            }
            this.base = URI.create(baseUrl.endsWith("/") ? baseUrl : baseUrl + "/");
        }

        /**
         * Sets the bearer token.
         *
         * @param value the token, or null for a service with none
         * @return this builder
         */
        public Builder token(String value) {
            this.token = value;
            return this;
        }

        /**
         * Sets the connect and request timeout of one attempt.
         *
         * @param value the timeout
         * @return this builder
         */
        public Builder timeout(Duration value) {
            this.timeout = value;
            return this;
        }

        /**
         * Sets the longest a call may take in all: every attempt, and every wait between them.
         *
         * <p>Each attempt is given the smaller of its timeout and what is left, a backoff that would
         * outlast what is left is not waited, and the call gives up when nothing is. Without one, a
         * call can take {@code maxAttempts} timeouts and the backoffs between them.
         *
         * @param value the deadline, or null for none
         * @return this builder
         */
        public Builder deadline(Duration value) {
            if (value != null && (value.isZero() || value.isNegative())) {
                throw new IllegalArgumentException("a deadline must be positive, got " + value);
            }
            this.deadline = value;
            return this;
        }

        /**
         * Sets how many times a call is attempted.
         *
         * <p>Every attempt carries the same idempotency key, so a command is executed once however
         * many attempts it takes to hear about it.
         *
         * @param value at least 1
         * @return this builder
         */
        public Builder maxAttempts(int value) {
            if (value < 1) {
                throw new IllegalArgumentException("maxAttempts must be at least 1");
            }
            this.maxAttempts = value;
            return this;
        }

        /**
         * Sets the first backoff interval; each attempt doubles it, with jitter.
         *
         * @param value the interval
         * @return this builder
         */
        public Builder backoff(Duration value) {
            this.backoff = value;
            return this;
        }

        /**
         * Builds the client.
         *
         * @return the client
         */
        public TillClient build() {
            return new TillClient(this);
        }
    }
}
