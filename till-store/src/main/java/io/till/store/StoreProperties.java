package io.till.store;

import io.till.store.catalogue.Games;
import java.time.Duration;
import java.time.Period;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Everything the store needs pointing at, in one typed place.
 *
 * <p>A record with defaults rather than scattered {@code @Value} fields, so that a missing or
 * malformed setting fails at startup with the property's name in the message, instead of at the
 * first request that happens to need it.
 *
 * @param till where the ledger is, and the two credentials the store holds for it
 * @param kafka where the ledger's events come from
 * @param auth who signs customers in
 * @param checkout the rules a basket has to satisfy
 * @param demo stock to seed on startup, for the demonstration stack only
 * @param catalogue how the catalogue's reads are cached
 * @param sales how long the sales roll-up's monthly partitions are kept, and how its maintenance job runs
 */
@ConfigurationProperties(prefix = "store")
public record StoreProperties(
        @DefaultValue Till till,
        @DefaultValue Kafka kafka,
        @DefaultValue Auth auth,
        @DefaultValue Checkout checkout,
        @DefaultValue Demo demo,
        @DefaultValue Catalogue catalogue,
        @DefaultValue Sales sales) {

    /**
     * The catalogue.
     *
     * @param cache its read cache, in the same Valkey as the sessions
     */
    public record Catalogue(@DefaultValue Cache cache) {}

    /**
     * How long each kind of catalogue answer is kept. docs/operations.md has the reasoning.
     *
     * @param enabled whether to cache at all; off, every read asks the database
     * @param stable what changes only with a release: a game, the featured and newest rows, genres
     * @param sales what moves with sales: best sellers, and the related games ranked by them
     * @param searches search results and their facets, the long tail
     * @param jitter how far each expiry is moved, either way, as a fraction of it, so that answers
     *     written together do not all expire together
     */
    public record Cache(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("10m") Duration stable,
            @DefaultValue("60s") Duration sales,
            @DefaultValue("60s") Duration searches,
            @DefaultValue("0.1") double jitter) {

        public Cache {
            if (jitter < 0 || jitter >= 1) {
                throw new IllegalArgumentException("store.catalogue.cache.jitter must be in [0, 1), got " + jitter);
            }
        }
    }

    /**
     * The ledger.
     *
     * @param baseUrl the till service
     * @param token the client token, used for every customer checkout. It can reserve, commit and
     *     release — the three things a shop does — and nothing else
     * @param adminToken the admin token, used only behind {@code /api/ops}, which only an operator can
     *     reach. Held as a separate client of a separate type, so that no code path can pick up the
     *     admin credential by accident when it meant the customer one
     * @param timeout how long one attempt may take
     * @param deadline how long one call may take in all, retries included: what a customer at the
     *     checkout is kept waiting before being told to try again. Without it, a ledger that has stopped
     *     answering costs every retry its full timeout
     */
    public record Till(
            @DefaultValue("http://127.0.0.1:8080") String baseUrl,
            @DefaultValue("") String token,
            @DefaultValue("") String adminToken,
            @DefaultValue("5s") Duration timeout,
            @DefaultValue("5s") Duration deadline) {}

    /**
     * The event stream.
     *
     * @param bootstrapServers the broker list; blank means do not consume at all, and availability
     *     stays at whatever was last projected
     * @param topic the topic till publishes to
     * @param groupId the consumer group; every instance of this service shares one, so each event is
     *     projected once rather than once per instance
     * @param pollTimeout how long a poll waits before coming back empty
     */
    public record Kafka(
            @DefaultValue("") String bootstrapServers,
            @DefaultValue("till.events") String topic,
            @DefaultValue("till-store") String groupId,
            @DefaultValue("500ms") Duration pollTimeout) {

        /**
         * @return whether a broker was configured
         */
        public boolean isConfigured() {
            return !bootstrapServers.isBlank();
        }
    }

    /**
     * Sign-in.
     *
     * @param oidc the identity provider
     * @param groupsClaim the claim carrying group membership. {@code cognito:groups} is what Amazon
     *     Cognito issues, and the local Keycloak realm is configured to issue the same name — so the
     *     code that decides who is an operator is identical in both, rather than tested against one
     *     provider and deployed against another
     * @param adminGroup the group whose members may use the operator console
     */
    public record Auth(
            @DefaultValue Oidc oidc,
            @DefaultValue("cognito:groups") String groupsClaim,
            @DefaultValue("admins") String adminGroup) {}

    /**
     * The OpenID Connect provider.
     *
     * <p>Two ways to configure it, and which one applies is decided by what is set:
     *
     * <ul>
     *   <li><b>Discovery</b> — only {@code issuerUri}. The provider's metadata is fetched at startup.
     *       This is how Amazon Cognito is configured, and how any provider reachable at one URL from
     *       everywhere should be.
     *   <li><b>Explicit endpoints</b> — {@code issuerUri} plus the four endpoint URLs, and nothing is
     *       fetched. This exists for one reason: in a container network, the browser reaches the
     *       provider at one address and this service reaches it at another, so a single discovery
     *       document cannot be right for both. The issuer is still checked against every ID token.
     * </ul>
     *
     * @param issuerUri the issuer; always required, and always checked against the token's {@code iss}
     * @param clientId this application's client id
     * @param clientSecret this application's client secret. A confidential client, because the code
     *     exchange happens here on the server and never in the browser
     * @param scopes what to ask for
     * @param authorizationUri where the browser is sent to sign in (explicit mode)
     * @param tokenUri where this service exchanges the code (explicit mode)
     * @param jwkSetUri where the signing keys are (explicit mode)
     * @param userInfoUri the user info endpoint (explicit mode)
     * @param logoutUri where to send the browser after the session here has ended, so that it is ended
     *     at the provider too. A template: {@code {clientId}}, {@code {baseUrl}} and {@code {idToken}}
     *     are filled in. Needed because Cognito does not publish a standard end-session endpoint, so
     *     "read it from discovery" is not something that works everywhere
     */
    public record Oidc(
            @DefaultValue("") String issuerUri,
            @DefaultValue("") String clientId,
            @DefaultValue("") String clientSecret,
            @DefaultValue({"openid", "profile", "email"}) List<String> scopes,
            @DefaultValue("") String authorizationUri,
            @DefaultValue("") String tokenUri,
            @DefaultValue("") String jwkSetUri,
            @DefaultValue("") String userInfoUri,
            @DefaultValue("") String logoutUri) {

        /**
         * @return whether the endpoints are given rather than discovered
         */
        public boolean explicit() {
            return !authorizationUri.isBlank();
        }
    }

    /**
     * What a basket may contain.
     *
     * @param holdFor how long a placed order holds its stock before the ledger gives it back
     * @param maxLines distinct games per order
     * @param maxQuantity copies of one game per order. Ten, as the schema also enforces: without a
     *     limit, one script can hold a release's whole stock for the length of a hold
     * @param currency every price in the catalogue is in this currency
     */
    public record Checkout(
            @DefaultValue("15m") Duration holdFor,
            @DefaultValue("20") int maxLines,
            @DefaultValue("10") int maxQuantity,
            @DefaultValue("USD") String currency) {

        public Checkout {
            if (holdFor.isNegative() || holdFor.isZero()) {
                throw new IllegalArgumentException("store.checkout.hold-for must be positive");
            }
            if (maxLines < 1 || maxQuantity < 1) {
                throw new IllegalArgumentException("store.checkout limits must be at least 1");
            }
            if (maxQuantity > 10) {
                // The schema refuses more than ten per line; a setting that promised more would only
                // move the refusal from a clear 400 here to a constraint violation there.
                throw new IllegalArgumentException("store.checkout.max-quantity cannot exceed 10");
            }
        }
    }

    /**
     * Demonstration stock.
     *
     * @param seedStock whether to stock every catalogue game on startup. Off by default, and only the
     *     compose stack turns it on: a production store does not invent inventory
     * @param defaultStock units per game
     * @param stock per-game overrides, for a game that should be scarce enough to race for
     * @param shards how many rows the ledger should keep each game's stock in: 1 leaves it as it is,
     *     more splits every game once it is stocked, as an operator would a game about to be busy —
     *     which is every game, in a load test (docs/design/0009-hot-sku-shards.md)
     */
    public record Demo(
            @DefaultValue("false") boolean seedStock,
            @DefaultValue("50") long defaultStock,
            @DefaultValue Map<String, Long> stock,
            @DefaultValue("1") int shards) {

        public Demo {
            stock = stock == null ? Map.of() : Map.copyOf(stock);
            if (shards < 1 || shards > 64) {
                throw new IllegalArgumentException("store.demo.shards must be between 1 and 64, got " + shards);
            }
        }

        /**
         * @param sku a catalogue game
         * @return how many units to seed it with
         */
        public long unitsFor(String sku) {
            return stock.getOrDefault(sku, defaultStock);
        }
    }

    /**
     * The sales roll-up, {@code store_sales_daily} ({@link io.till.store.sales.SalesPartitionMaintenance},
     * {@code docs/design/0010-sales-partitions.md}).
     *
     * @param retention how long a month's partition is kept before it is dropped. Generous by
     *     default, and the setting to raise rather than lower: every "best sellers" read depends on
     *     the last {@link Games#SALES_WINDOW_DAYS} days still having a partition, so a shorter
     *     retention is refused rather than quietly dropping a window a live query still needs
     */
    public record Sales(@DefaultValue("13m") Period retention) {

        public Sales {
            if (retention.isZero() || retention.isNegative()) {
                throw new IllegalArgumentException("store.sales.retention must be positive");
            }
            // A deliberately rough conversion: it only has to catch a retention that is obviously
            // too short (days, where months were meant), not measure the window to the day. The
            // partition maintenance job itself compares real calendar dates, never this estimate.
            long approxDays = retention.getDays() + 30L * retention.getMonths() + 365L * retention.getYears();
            if (approxDays < Games.SALES_WINDOW_DAYS) {
                throw new IllegalArgumentException("store.sales.retention must be at least " + Games.SALES_WINDOW_DAYS
                        + " days, the best-seller window it would otherwise cut a partition out from under");
            }
        }
    }
}
