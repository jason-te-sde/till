package io.till.server;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Everything about this service that an operator sets.
 *
 * <p>Defaults are the ones a small deployment should keep. {@code docs/operations.md} says what to
 * change and why; the comments here say what each one costs.
 *
 * @param maxAttempts how many times a command is decided before it gives up and answers 503. Higher
 *     trades tail latency for a lower failure rate under contention; there is no backoff between
 *     attempts, because a conflict means somebody else committed and waiting helps nobody
 * @param reclaimLimit how many expired holds a passing command writes off. Higher returns stock to
 *     available sooner and makes each decision touch more rows, which makes conflicts likelier
 * @param defaultTtl how long a hold lasts when the caller does not say
 * @param maxTtl the longest a caller may ask for. Beyond this a hold is a stock level, not a hold
 * @param insecure allows binding a non-loopback address with no tokens. For a laptop, and named so
 *     that it cannot appear in a production configuration by accident
 * @param auth the two tokens
 * @param sweeper the background expiry job
 * @param outbox the background publisher
 * @param web how the browser console is served
 * @param retention how long history is kept
 */
@ConfigurationProperties(prefix = "till")
public record TillProperties(
        @DefaultValue("8") int maxAttempts,
        @DefaultValue("32") int reclaimLimit,
        @DefaultValue("15m") Duration defaultTtl,
        @DefaultValue("24h") Duration maxTtl,
        @DefaultValue("false") boolean insecure,
        @DefaultValue Auth auth,
        @DefaultValue Sweeper sweeper,
        @DefaultValue Outbox outbox,
        @DefaultValue Web web,
        @DefaultValue RetentionPolicy retention) {

    public TillProperties {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("till.max-attempts must be at least 1");
        }
        if (reclaimLimit < 0) {
            throw new IllegalArgumentException("till.reclaim-limit must not be negative");
        }
        if (defaultTtl.isNegative() || defaultTtl.isZero()) {
            throw new IllegalArgumentException("till.default-ttl must be positive");
        }
        if (maxTtl.compareTo(defaultTtl) < 0) {
            throw new IllegalArgumentException("till.max-ttl must not be shorter than till.default-ttl");
        }
    }

    /**
     * The two tokens.
     *
     * <p>Two rather than one because ejecting stock from the ledger is not the same privilege as
     * taking a hold on it: the checkout path needs the first and should never hold the second.
     *
     * @param clientToken required by every endpoint under {@code /v1}
     * @param adminToken required additionally by the endpoints that change stock directly
     */
    public record Auth(String clientToken, String adminToken) {

        /**
         * Whether a token has been configured at all.
         *
         * @return true if a client token is set
         */
        public boolean isConfigured() {
            return clientToken != null && !clientToken.isBlank();
        }

        /**
         * Whether the privileged operations have a token of their own.
         *
         * @return true if an admin token is set
         */
        public boolean isAdminConfigured() {
            return adminToken != null && !adminToken.isBlank();
        }
    }

    /**
     * How long history is kept.
     *
     * <p>A duration of zero means keep it forever, which is the default for reservations and not for
     * the other two. See {@code RetentionSweeper} for why they differ.
     *
     * @param enabled whether to delete anything at all
     * @param interval how often a pass runs
     * @param batch rows per statement; small enough that a delete does not hold a lock long enough
     *     for anything else to notice
     * @param passes batches per table per run, so a first pass over years of history is bounded and
     *     comes back for the rest rather than holding a connection for an hour
     * @param idempotency how long a key is remembered. <b>Deleting one means a caller retrying that
     *     command executes it again</b>, so this must be comfortably longer than the longest client
     *     retry window — a fact about your callers, not about till
     * @param outbox how long published events are kept. Unpublished rows are never deleted
     * @param reservations how long finished reservations are kept. Zero, meaning forever, because
     *     they are the record of what was held and by whom
     */
    public record RetentionPolicy(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("1h") Duration interval,
            @DefaultValue("1000") int batch,
            @DefaultValue("20") int passes,
            @DefaultValue("7d") Duration idempotency,
            @DefaultValue("30d") Duration outbox,
            @DefaultValue("0s") Duration reservations) {

        public RetentionPolicy {
            if (batch < 1) {
                throw new IllegalArgumentException("till.retention.batch must be at least 1");
            }
            if (passes < 1) {
                throw new IllegalArgumentException("till.retention.passes must be at least 1");
            }
            if (idempotency.isNegative() || outbox.isNegative() || reservations.isNegative()) {
                throw new IllegalArgumentException("a retention period must not be negative");
            }
        }
    }

    /**
     * How the browser console is served.
     *
     * @param corsOrigins origins allowed to call the API from a browser. Empty by default, and it
     *     should stay empty: the console is served by this service, so it is same-origin and needs
     *     none. This exists for the case where somebody hosts the console separately, and a wildcard
     *     is deliberately not supported — an inventory API that any page may call is an inventory API
     *     any page may read
     */
    public record Web(@DefaultValue List<String> corsOrigins) {

        public Web {
            corsOrigins = List.copyOf(corsOrigins);
            if (corsOrigins.contains("*")) {
                throw new IllegalArgumentException(
                        "till.web.cors-origins does not accept '*'; name the origins that may call this API");
            }
        }
    }

    /**
     * The background expiry job.
     *
     * <p>Not required for correctness: a hold past its deadline stops counting the moment any
     * command looks at it. This returns the stock to {@code available} sooner than the next command
     * touching those SKUs would.
     *
     * @param enabled whether to run it
     * @param interval how often
     * @param batch how many holds to write off per run
     */
    public record Sweeper(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("5s") Duration interval,
            @DefaultValue("200") int batch) {}

    /**
     * The background publisher.
     *
     * @param enabled whether to run it
     * @param interval how often
     * @param batch how many events to publish per run
     */
    public record Outbox(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("1s") Duration interval,
            @DefaultValue("200") int batch) {}
}
