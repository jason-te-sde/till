package io.till.store;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import tools.jackson.databind.json.JsonMapper;

/**
 * An OpenID provider small enough to read, for the part of signing in the store does on its own:
 * redeeming the code.
 *
 * <p>Two endpoints — the token endpoint and the key set — served from this JVM on a free port. There
 * is no login page: {@link #approve} stands in for a customer who has just typed their password, and
 * returns the callback the provider would redirect their browser to. Everything after that is real —
 * the store redeeming the code, checking the ID token's signature, issuer, audience and nonce, and
 * turning the groups claim into roles.
 *
 * <p>It is as strict as a real provider in each way the store's security depends on. A code is
 * redeemed once; only with the verifier whose hash is the challenge it was issued against (PKCE); only
 * for the redirect URI it was issued to; and only by a client presenting the right secret. A store
 * that forgot any of those would fail to sign in here, which is the point of having it.
 */
final class FakeIdentityProvider {

    static final String CLIENT_ID = "till-store";
    static final String CLIENT_SECRET = "test-only-secret";

    private static final String REALM = "/realms/till";
    private static final String OIDC = REALM + "/protocol/openid-connect";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final HttpServer server;
    private final String base;
    private final RSAKey signingKey = generateKey();
    /** Published nowhere: what a forger has. */
    private final RSAKey forgersKey = generateKey();
    private final Map<String, Grant> grants = new ConcurrentHashMap<>();
    private final List<String> issued = new CopyOnWriteArrayList<>();

    private FakeIdentityProvider(HttpServer server) {
        this.server = server;
        this.base = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext(OIDC + "/token", this::token);
        server.createContext(OIDC + "/certs", this::keys);
    }

    /**
     * @return a provider listening on a free loopback port
     */
    static FakeIdentityProvider start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
            server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            FakeIdentityProvider provider = new FakeIdentityProvider(server);
            server.start();
            return provider;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    String issuer() {
        return base + REALM;
    }

    String authorizationUri() {
        return base + OIDC + "/auth";
    }

    String tokenUri() {
        return base + OIDC + "/token";
    }

    String jwkSetUri() {
        return base + OIDC + "/certs";
    }

    String logoutUri() {
        return base + OIDC + "/logout";
    }

    /**
     * @return every token this provider has handed out, access and ID alike
     */
    List<String> issuedTokens() {
        return List.copyOf(issued);
    }

    /**
     * What the login page does once a customer has proved who they are: issues a code bound to this
     * authorization request, and sends the browser back with it.
     *
     * @param authorizationRequest where the store redirected the browser
     * @param who who signed in
     * @return where the provider redirects the browser next
     */
    URI approve(URI authorizationRequest, Identity who) {
        return issue(authorizationRequest, who, signingKey);
    }

    /**
     * The same, but the ID token that code redeems for is signed by a key this provider never
     * published — a forgery.
     *
     * @param authorizationRequest where the store redirected the browser
     * @param who who the forger claims to be
     * @return where the browser goes next
     */
    URI approveForged(URI authorizationRequest, Identity who) {
        return issue(authorizationRequest, who, forgersKey);
    }

    /**
     * What happens when the customer clicks "cancel" on the provider's login page.
     *
     * @param authorizationRequest where the store redirected the browser
     * @return where the provider redirects the browser next
     */
    URI deny(URI authorizationRequest) {
        Map<String, String> request = query(authorizationRequest);
        return URI.create(request.get("redirect_uri") + "?error=access_denied&state=" + encode(request.get("state")));
    }

    private URI issue(URI authorizationRequest, Identity who, RSAKey key) {
        Map<String, String> request = query(authorizationRequest);
        require("code".equals(request.get("response_type")), "response_type must be code: " + request);
        require(CLIENT_ID.equals(request.get("client_id")), "unknown client: " + request);
        require("S256".equals(request.get("code_challenge_method")), "PKCE with S256 is required: " + request);
        require(request.get("code_challenge") != null, "PKCE with S256 is required: " + request);
        require(request.get("nonce") != null, "a nonce is required: " + request);
        require(request.get("state") != null, "a state is required: " + request);
        String code = UUID.randomUUID().toString();
        grants.put(code, new Grant(who, request.get("nonce"), request.get("code_challenge"), request.get("redirect_uri"), key));
        return URI.create(request.get("redirect_uri") + "?code=" + encode(code) + "&state=" + encode(request.get("state")));
    }

    // --- endpoints ----------------------------------------------------------------------------------

    private void token(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (!"POST".equals(exchange.getRequestMethod())) {
                respond(exchange, 405, Map.of("error", "invalid_request"));
                return;
            }
            if (!clientAuthenticated(exchange.getRequestHeaders().getFirst("Authorization"))) {
                respond(exchange, 401, Map.of("error", "invalid_client"));
                return;
            }
            Map<String, String> form = form(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            if (!"authorization_code".equals(form.get("grant_type"))) {
                respond(exchange, 400, Map.of("error", "unsupported_grant_type"));
                return;
            }
            // Removed, not read: a code is good once.
            Grant grant = grants.remove(form.getOrDefault("code", ""));
            if (grant == null
                    || !grant.redirectUri().equals(form.get("redirect_uri"))
                    || form.get("code_verifier") == null
                    || !grant.codeChallenge().equals(s256(form.get("code_verifier")))) {
                respond(exchange, 400, Map.of("error", "invalid_grant"));
                return;
            }
            String accessToken = UUID.randomUUID().toString();
            String idToken = idToken(grant);
            issued.add(accessToken);
            issued.add(idToken);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("access_token", accessToken);
            body.put("token_type", "Bearer");
            body.put("expires_in", 300);
            body.put("scope", "openid profile email");
            body.put("id_token", idToken);
            respond(exchange, 200, body);
        }
    }

    private void keys(HttpExchange exchange) throws IOException {
        try (exchange) {
            byte[] body = new JWKSet(signingKey.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
        }
    }

    private String idToken(Grant grant) {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(issuer())
                .subject(grant.who().subject())
                .audience(CLIENT_ID)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(Duration.ofMinutes(5))))
                .claim("auth_time", now.getEpochSecond())
                .claim("nonce", grant.nonce())
                .claim("name", grant.who().name())
                .claim("email", grant.who().email())
                .claim("cognito:groups", grant.who().groups())
                .build();
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signingKey.getKeyID()).build(), claims);
        try {
            jwt.sign(new RSASSASigner(grant.key()));
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
        return jwt.serialize();
    }

    private boolean clientAuthenticated(String authorization) {
        if (authorization == null || !authorization.startsWith("Basic ")) {
            return false;
        }
        String decoded = new String(Base64.getDecoder().decode(authorization.substring(6)), StandardCharsets.UTF_8);
        int colon = decoded.indexOf(':');
        return colon > 0
                && CLIENT_ID.equals(decode(decoded.substring(0, colon)))
                && CLIENT_SECRET.equals(decode(decoded.substring(colon + 1)));
    }

    // --- plumbing -----------------------------------------------------------------------------------

    private static void respond(HttpExchange exchange, int status, Map<String, ?> body) throws IOException {
        byte[] bytes = JSON.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private static Map<String, String> query(URI uri) {
        return form(uri.getRawQuery());
    }

    private static Map<String, String> form(String encoded) {
        Map<String, String> values = new LinkedHashMap<>();
        if (encoded == null || encoded.isEmpty()) {
            return values;
        }
        for (String pair : encoded.split("&")) {
            int equals = pair.indexOf('=');
            if (equals > 0) {
                values.put(decode(pair.substring(0, equals)), decode(pair.substring(equals + 1)));
            }
        }
        return values;
    }

    private static String s256(String verifier) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static RSAKey generateKey() {
        try {
            return new RSAKeyGenerator(2048).keyID(UUID.randomUUID().toString()).generate();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError("the store sent an authorization request no provider would accept: " + message);
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    /**
     * Somebody who can sign in.
     *
     * @param subject the provider's stable identifier for them
     * @param name their display name
     * @param email their email
     * @param groups the groups the provider puts in the ID token
     */
    record Identity(String subject, String name, String email, List<String> groups) {

        static Identity customer(String subject) {
            return new Identity(subject, "Player " + subject, subject + "@example.test", List.of());
        }

        static Identity operator(String subject) {
            return new Identity(subject, "Operator " + subject, subject + "@example.test", List.of("admins"));
        }
    }

    private record Grant(Identity who, String nonce, String codeChallenge, String redirectUri, RSAKey key) {}
}
