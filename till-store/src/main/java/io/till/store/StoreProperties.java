package io.till.store;

import java.time.Duration;
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
 */
@ConfigurationProperties(prefix = "store")
public record StoreProperties(
        @DefaultValue Till till,
        @DefaultValue Kafka kafka,
        @DefaultValue Auth auth,
        @DefaultValue Checkout checkout,
        @DefaultValue Demo demo) {

    /**
     * The ledger.
     *
     * @param baseUrl the till service
     * @param token the client token, used for every customer checkout. It can reserve, commit and
     *     release — the three things a shop does — and nothing else
     * @param adminToken the admin token, used only behind {@code /api/ops}, which only an operator can
     *     reach. Held as a separate client of a separate type, so that no code path can pick up the
     *     admin credential by accident when it meant the customer one
     * @param timeout per-request timeout
     */
    public record Till(
            @DefaultValue("http://127.0.0.1:8080") String baseUrl,
            @DefaultValue("") String token,
            @DefaultValue("") String adminToken,
            @DefaultValue("5s") Duration timeout) {}

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
     */
    public record Demo(
            @DefaultValue("false") boolean seedStock,
            @DefaultValue("50") long defaultStock,
            @DefaultValue Map<String, Long> stock) {

        public Demo {
            stock = stock == null ? Map.of() : Map.copyOf(stock);
        }

        /**
         * @param sku a catalogue game
         * @return how many units to seed it with
         */
        public long unitsFor(String sku) {
            return stock.getOrDefault(sku, defaultStock);
        }
    }
}
