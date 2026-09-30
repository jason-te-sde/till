package io.till.loadtest;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * An OpenID provider for the load test's shoppers to sign in with. It approves every authorization
 * request at once, without a password, and issues a properly signed ID token for whoever the request
 * names in {@code login_hint}.
 *
 * <p>It exists so that signing in can be part of the load without the load being Cognito's. The
 * store runs its whole side of the flow against it — the redirect, PKCE, the nonce, redeeming the
 * code with its client secret, and checking the ID token's signature, issuer and audience — so a
 * store that got any of that wrong would fail to sign anybody in here, as it would against Cognito.
 * What is left out is only the part a person does: typing a password into a form.
 *
 * <p><strong>It approves everybody.</strong> It is for a network that nothing but the load test can
 * reach, for as long as the test runs. It refuses to start without an issuer, a client and a secret
 * set explicitly, so that it cannot come up by accident as somebody's provider.
 *
 * <p>Deliberately small, and on the JDK alone: an HTTP server, RSA signatures and a few hundred
 * lines, with no dependency that could make the load generator's image the thing under test.
 */
public final class StandInProvider implements AutoCloseable {

    /** How long a code may wait to be redeemed. The store redeems it within milliseconds. */
    static final Duration CODE_LIFETIME = Duration.ofSeconds(60);

    /** How long an ID token is valid. The store checks it once, when the shopper signs in. */
    static final Duration TOKEN_LIFETIME = Duration.ofHours(1);

    /** A shopper's name: letters, digits and a little punctuation, so it can go into JSON as it is. */
    private static final Pattern SUBJECT = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    private static final Base64.Encoder BASE64URL = Base64.getUrlEncoder().withoutPadding();

    private final HttpServer server;
    private final ExecutorService handlers;
    private final ScheduledExecutorService sweeper;
    private final String issuer;
    private final String clientId;
    private final byte[] clientSecret;
    private final KeyPair key;
    private final String keyId = UUID.randomUUID().toString();
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Grant> grants = new ConcurrentHashMap<>();

    private StandInProvider(HttpServer server, String issuer, String clientId, String clientSecret, Clock clock) {
        this.server = server;
        this.issuer = issuer;
        this.clientId = clientId;
        this.clientSecret = clientSecret.getBytes(StandardCharsets.UTF_8);
        this.clock = clock;
        this.key = generateKey();
        this.handlers = Executors.newVirtualThreadPerTaskExecutor();
        this.sweeper = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("stand-in-sweeper").factory());
        server.createContext("/.well-known/openid-configuration", exchange -> handle(exchange, this::discovery));
        server.createContext("/authorize", exchange -> handle(exchange, this::authorize));
        server.createContext("/token", exchange -> handle(exchange, this::token));
        server.createContext("/certs", exchange -> handle(exchange, this::certs));
        server.createContext("/health", exchange -> handle(exchange, this::health));
        server.setExecutor(handlers);
        // Codes nobody redeemed — a shopper that gave up halfway — would otherwise stay forever.
        sweeper.scheduleWithFixedDelay(this::sweep, 30, 30, TimeUnit.SECONDS);
    }

    /**
     * Starts a provider.
     *
     * @param address where to listen; port 0 picks a free one
     * @param issuer the issuer to put in every token and in the discovery document, or {@code null}
     *     for {@code http://} and the address it ended up listening on
     * @param clientId the only client it will issue codes to
     * @param clientSecret that client's secret, which the token endpoint requires
     * @param clock the time tokens are issued at
     * @return the running provider
     * @throws IOException if it cannot listen there
     */
    public static StandInProvider start(
            InetSocketAddress address, String issuer, String clientId, String clientSecret, Clock clock)
            throws IOException {
        HttpServer server = HttpServer.create(address, 1024);
        String resolved = issuer != null
                ? stripTrailingSlash(issuer)
                : "http://" + address.getHostString() + ":" + server.getAddress().getPort();
        StandInProvider provider = new StandInProvider(
                server, resolved, Objects.requireNonNull(clientId), Objects.requireNonNull(clientSecret), clock);
        server.start();
        return provider;
    }

    /**
     * Runs one from the environment: {@code STANDIN_ISSUER}, {@code STANDIN_CLIENT_ID} and
     * {@code STANDIN_CLIENT_SECRET}, all required, and {@code STANDIN_PORT}, 8090 unless set.
     *
     * @param args ignored
     * @throws IOException if it cannot listen
     */
    public static void main(String[] args) throws IOException {
        Map<String, String> env = System.getenv();
        String issuer = required(env, "STANDIN_ISSUER");
        int port = Integer.parseInt(env.getOrDefault("STANDIN_PORT", "8090"));
        StandInProvider provider = start(
                new InetSocketAddress(InetAddress.getByName("0.0.0.0"), port),
                issuer,
                required(env, "STANDIN_CLIENT_ID"),
                required(env, "STANDIN_CLIENT_SECRET"),
                Clock.systemUTC());
        Runtime.getRuntime().addShutdownHook(new Thread(provider::close, "stand-in-shutdown"));
        System.out.println("stand-in OpenID provider for the load test, approving every sign-in, at " + issuer
                + " (port " + port + ")");
    }

    /** @return the issuer, which is also where the endpoints are */
    public String issuer() {
        return issuer;
    }

    /** @return the port it is listening on */
    public int port() {
        return server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
        sweeper.shutdownNow();
        handlers.shutdown();
    }

    // --- endpoints ----------------------------------------------------------------------------

    private Response discovery(HttpExchange exchange) {
        if (!"GET".equals(exchange.getRequestMethod())) {
            return Response.error(405, "invalid_request", "GET only");
        }
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("issuer", issuer);
        document.put("authorization_endpoint", issuer + "/authorize");
        document.put("token_endpoint", issuer + "/token");
        document.put("jwks_uri", issuer + "/certs");
        document.put("response_types_supported", new String[] {"code"});
        document.put("subject_types_supported", new String[] {"public"});
        document.put("id_token_signing_alg_values_supported", new String[] {"RS256"});
        document.put("code_challenge_methods_supported", new String[] {"S256"});
        document.put("token_endpoint_auth_methods_supported", new String[] {"client_secret_basic", "client_secret_post"});
        document.put("scopes_supported", new String[] {"openid", "profile", "email"});
        return Response.json(200, Json.object(document));
    }

    /**
     * Approves the request on the spot. What cannot be sent back to the client — an unknown client, a
     * missing redirect — is refused to the caller; everything else goes back to the redirect URI, as
     * RFC 6749 says, so the store sees a refusal the way it would see Cognito's.
     */
    private Response authorize(HttpExchange exchange) {
        if (!"GET".equals(exchange.getRequestMethod())) {
            return Response.error(405, "invalid_request", "GET only");
        }
        Map<String, String> query = parameters(exchange.getRequestURI().getRawQuery());
        String redirectUri = query.get("redirect_uri");
        if (!clientId.equals(query.get("client_id"))) {
            return Response.error(400, "unauthorized_client", "unknown client");
        }
        if (redirectUri == null || !(redirectUri.startsWith("http://") || redirectUri.startsWith("https://"))) {
            return Response.error(400, "invalid_request", "an absolute redirect_uri is required");
        }
        String state = query.get("state");
        if (!"code".equals(query.get("response_type"))) {
            return Response.redirect(back(redirectUri, "error", "unsupported_response_type", state));
        }
        String challenge = query.get("code_challenge");
        if (challenge == null || !"S256".equals(query.get("code_challenge_method"))) {
            return Response.redirect(back(redirectUri, "error", "invalid_request", state));
        }
        String subject = query.getOrDefault("login_hint", "shopper-" + UUID.randomUUID());
        if (!SUBJECT.matcher(subject).matches()) {
            return Response.redirect(back(redirectUri, "error", "invalid_request", state));
        }
        String code = randomToken();
        grants.put(code, new Grant(subject, query.get("nonce"), challenge, redirectUri, clock.instant().plus(CODE_LIFETIME)));
        return Response.redirect(back(redirectUri, "code", code, state));
    }

    /** Redeems a code: once, by the client it was issued to, with the verifier its challenge came from. */
    private Response token(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            return Response.error(405, "invalid_request", "POST only");
        }
        Map<String, String> form;
        try (InputStream body = exchange.getRequestBody()) {
            form = parameters(new String(body.readNBytes(64 * 1024), StandardCharsets.UTF_8));
        }
        if (!authenticated(exchange.getRequestHeaders().getFirst("Authorization"), form)) {
            return Response.error(401, "invalid_client", "client authentication failed")
                    .with("WWW-Authenticate", "Basic realm=\"stand-in\"");
        }
        if (!"authorization_code".equals(form.get("grant_type"))) {
            return Response.error(400, "unsupported_grant_type", "authorization_code only");
        }
        String code = form.get("code");
        Grant grant = code == null ? null : grants.remove(code);
        Instant now = clock.instant();
        if (grant == null || now.isAfter(grant.expires())) {
            return Response.error(400, "invalid_grant", "unknown, used or expired code");
        }
        if (!grant.redirectUri().equals(form.get("redirect_uri"))) {
            return Response.error(400, "invalid_grant", "redirect_uri does not match the authorization request");
        }
        String verifier = form.get("code_verifier");
        if (verifier == null || !MessageDigest.isEqual(
                s256(verifier).getBytes(StandardCharsets.US_ASCII), grant.challenge().getBytes(StandardCharsets.US_ASCII))) {
            return Response.error(400, "invalid_grant", "code_verifier does not match the challenge");
        }
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("access_token", randomToken());
        answer.put("token_type", "Bearer");
        answer.put("expires_in", TOKEN_LIFETIME.toSeconds());
        answer.put("scope", "openid profile email");
        answer.put("id_token", idToken(grant, now));
        return Response.json(200, Json.object(answer)).with("Cache-Control", "no-store").with("Pragma", "no-cache");
    }

    private Response certs(HttpExchange exchange) {
        RSAPublicKey publicKey = (RSAPublicKey) key.getPublic();
        Map<String, Object> jwk = new LinkedHashMap<>();
        jwk.put("kty", "RSA");
        jwk.put("use", "sig");
        jwk.put("alg", "RS256");
        jwk.put("kid", keyId);
        jwk.put("n", unsigned(publicKey.getModulus()));
        jwk.put("e", unsigned(publicKey.getPublicExponent()));
        return Response.json(200, "{\"keys\":[" + Json.object(jwk) + "]}");
    }

    private Response health(HttpExchange exchange) {
        return new Response(200, "text/plain", "ok\n".getBytes(StandardCharsets.UTF_8), Map.of(), null);
    }

    // --- tokens -------------------------------------------------------------------------------

    private String idToken(Grant grant, Instant now) {
        String[] name = grant.subject().split("-", 2);
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", issuer);
        claims.put("sub", grant.subject());
        claims.put("aud", clientId);
        claims.put("iat", now.getEpochSecond());
        claims.put("auth_time", now.getEpochSecond());
        claims.put("exp", now.plus(TOKEN_LIFETIME).getEpochSecond());
        if (grant.nonce() != null) {
            claims.put("nonce", grant.nonce());
        }
        claims.put("email", grant.subject() + "@loadtest.invalid");
        claims.put("email_verified", Boolean.TRUE);
        claims.put("preferred_username", grant.subject());
        claims.put("given_name", capitalised(name[0]));
        claims.put("name", name.length == 2 ? capitalised(name[0]) + " " + name[1] : capitalised(name[0]));

        Map<String, Object> header = new LinkedHashMap<>();
        header.put("alg", "RS256");
        header.put("typ", "JWT");
        header.put("kid", keyId);

        String signingInput = encode(Json.object(header)) + "." + encode(Json.object(claims));
        try {
            Signature signer = Signature.getInstance("SHA256withRSA");
            signer.initSign(key.getPrivate());
            signer.update(signingInput.getBytes(StandardCharsets.US_ASCII));
            return signingInput + "." + BASE64URL.encodeToString(signer.sign());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("RS256 is part of every JDK", e);
        }
    }

    private boolean authenticated(String authorization, Map<String, String> form) {
        String id;
        String secret;
        if (authorization != null && authorization.regionMatches(true, 0, "Basic ", 0, 6)) {
            String decoded;
            try {
                decoded = new String(Base64.getDecoder().decode(authorization.substring(6).trim()), StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                return false;
            }
            int colon = decoded.indexOf(':');
            if (colon < 0) {
                return false;
            }
            // RFC 6749 §2.3.1: both halves are form-encoded before they are joined.
            id = URLDecoder.decode(decoded.substring(0, colon), StandardCharsets.UTF_8);
            secret = URLDecoder.decode(decoded.substring(colon + 1), StandardCharsets.UTF_8);
        } else {
            id = form.get("client_id");
            secret = form.get("client_secret");
        }
        return clientId.equals(id)
                && secret != null
                && MessageDigest.isEqual(clientSecret, secret.getBytes(StandardCharsets.UTF_8));
    }

    private void sweep() {
        Instant now = clock.instant();
        grants.values().removeIf(grant -> now.isAfter(grant.expires()));
    }

    // --- plumbing -----------------------------------------------------------------------------

    @FunctionalInterface
    private interface Endpoint {
        Response serve(HttpExchange exchange) throws IOException;
    }

    private static void handle(HttpExchange exchange, Endpoint endpoint) throws IOException {
        try (exchange) {
            Response response;
            try {
                response = endpoint.serve(exchange);
            } catch (RuntimeException e) {
                response = Response.error(500, "server_error", e.getClass().getSimpleName());
            }
            response.headers().forEach((name, value) -> exchange.getResponseHeaders().set(name, value));
            if (response.location() != null) {
                exchange.getResponseHeaders().set("Location", response.location());
            }
            if (response.contentType() != null) {
                exchange.getResponseHeaders().set("Content-Type", response.contentType());
            }
            byte[] body = response.body();
            exchange.sendResponseHeaders(response.status(), body.length == 0 ? -1 : body.length);
            if (body.length > 0) {
                exchange.getResponseBody().write(body);
            }
        }
    }

    private record Response(int status, String contentType, byte[] body, Map<String, String> headers, String location) {

        static Response json(int status, String json) {
            return new Response(status, "application/json", json.getBytes(StandardCharsets.UTF_8), Map.of(), null);
        }

        static Response error(int status, String error, String description) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("error", error);
            body.put("error_description", description);
            return json(status, Json.object(body));
        }

        static Response redirect(String location) {
            return new Response(302, null, new byte[0], Map.of(), location);
        }

        Response with(String name, String value) {
            Map<String, String> more = new LinkedHashMap<>(headers);
            more.put(name, value);
            return new Response(status, contentType, body, Map.copyOf(more), location);
        }
    }

    private record Grant(String subject, String nonce, String challenge, String redirectUri, Instant expires) {}

    private static String back(String redirectUri, String name, String value, String state) {
        StringBuilder uri = new StringBuilder(redirectUri).append(redirectUri.contains("?") ? '&' : '?');
        uri.append(name).append('=').append(URLEncoder.encode(value, StandardCharsets.UTF_8));
        if (state != null) {
            uri.append("&state=").append(URLEncoder.encode(state, StandardCharsets.UTF_8));
        }
        return uri.toString();
    }

    static Map<String, String> parameters(String raw) {
        Map<String, String> parameters = new LinkedHashMap<>();
        if (raw == null || raw.isEmpty()) {
            return parameters;
        }
        for (String pair : raw.split("&")) {
            int equals = pair.indexOf('=');
            String name = URLDecoder.decode(equals < 0 ? pair : pair.substring(0, equals), StandardCharsets.UTF_8);
            String value = equals < 0 ? "" : URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8);
            parameters.putIfAbsent(name, value);
        }
        return parameters;
    }

    static String s256(String verifier) {
        try {
            return BASE64URL.encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SHA-256 is part of every JDK", e);
        }
    }

    private String randomToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return BASE64URL.encodeToString(bytes);
    }

    private static String encode(String json) {
        return BASE64URL.encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    /** A JWK's big-endian integer: no sign byte, no padding. */
    private static String unsigned(BigInteger value) {
        byte[] bytes = value.toByteArray();
        if (bytes.length > 1 && bytes[0] == 0) {
            byte[] trimmed = new byte[bytes.length - 1];
            System.arraycopy(bytes, 1, trimmed, 0, trimmed.length);
            bytes = trimmed;
        }
        return BASE64URL.encodeToString(bytes);
    }

    private static KeyPair generateKey() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("RSA is part of every JDK", e);
        }
    }

    private static String capitalised(String word) {
        return word.isEmpty() ? word : Character.toUpperCase(word.charAt(0)) + word.substring(1);
    }

    private static String stripTrailingSlash(String issuer) {
        return issuer.endsWith("/") ? issuer.substring(0, issuer.length() - 1) : issuer;
    }

    private static String required(Map<String, String> env, String name) {
        String value = env.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " must be set: this provider approves every sign-in, and"
                    + " starts only where it has been configured on purpose");
        }
        return value;
    }
}
