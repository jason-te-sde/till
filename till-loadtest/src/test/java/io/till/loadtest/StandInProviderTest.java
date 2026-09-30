package io.till.loadtest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.RSAPublicKeySpec;
import java.time.Clock;
import java.util.Base64;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The provider does what the store's side of signing in depends on: a code for the redirect URI it
 * was asked for, redeemable once, by the right client, with the right verifier, for an ID token signed
 * by the key it publishes. The store's own tests prove the store checks all of that; these prove the
 * load test's provider gives it something real to check.
 */
class StandInProviderTest {

    private static final String CLIENT = "till-store";
    private static final String SECRET = "stand-in-secret";
    private static final String CALLBACK = "http://store.test/login/oauth2/code/idp";
    private static final String VERIFIER = "a-verifier-that-is-long-enough-to-satisfy-rfc-7636-43-chars";

    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    private StandInProvider provider;

    @BeforeEach
    void start() throws IOException {
        provider = StandInProvider.start(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), null, CLIENT, SECRET, Clock.systemUTC());
    }

    @AfterEach
    void stop() {
        provider.close();
    }

    @Test
    void signsInWhoeverTheRequestNamesWithATokenSignedByTheKeyItPublishes() throws Exception {
        URI callback = authorize(Map.of("login_hint", "shopper-17", "state", "st4te", "nonce", "n0nce"));
        Map<String, String> query = StandInProvider.parameters(callback.getRawQuery());
        assertEquals("st4te", query.get("state"));
        assertTrue(callback.toString().startsWith(CALLBACK + "?code="), callback::toString);

        HttpResponse<String> answer = redeem(query.get("code"), VERIFIER, basic(CLIENT, SECRET));
        assertEquals(200, answer.statusCode(), answer::body);
        assertEquals("no-store", answer.headers().firstValue("Cache-Control").orElse(""));

        String[] token = field(answer.body(), "id_token").split("\\.");
        String claims = new String(Base64.getUrlDecoder().decode(token[1]), StandardCharsets.UTF_8);
        assertEquals(provider.issuer(), field(claims, "iss"));
        assertEquals("shopper-17", field(claims, "sub"));
        assertEquals(CLIENT, field(claims, "aud"));
        assertEquals("n0nce", field(claims, "nonce"));
        assertEquals("Shopper 17", field(claims, "name"));

        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(publishedKey());
        verifier.update((token[0] + "." + token[1]).getBytes(StandardCharsets.US_ASCII));
        assertTrue(verifier.verify(Base64.getUrlDecoder().decode(token[2])), "the ID token is signed by the published key");
    }

    @Test
    void aCodeIsRedeemedOnceAndOnlyWithItsVerifier() throws Exception {
        String code = code(authorize(Map.of("login_hint", "shopper-1")));
        assertEquals(400, redeem(code, "not-the-verifier-it-was-challenged-with-at-all", basic(CLIENT, SECRET)).statusCode());
        // A wrong verifier used the code up: a code that could be retried would let a thief guess.
        assertEquals(400, redeem(code, VERIFIER, basic(CLIENT, SECRET)).statusCode());

        String another = code(authorize(Map.of("login_hint", "shopper-2")));
        assertEquals(200, redeem(another, VERIFIER, basic(CLIENT, SECRET)).statusCode());
        HttpResponse<String> replay = redeem(another, VERIFIER, basic(CLIENT, SECRET));
        assertEquals(400, replay.statusCode());
        assertEquals("invalid_grant", field(replay.body(), "error"));
    }

    @Test
    void onlyTheClientWithItsSecretMayRedeem() throws Exception {
        String code = code(authorize(Map.of("login_hint", "shopper-3")));
        HttpResponse<String> refused = redeem(code, VERIFIER, basic(CLIENT, "guessed"));
        assertEquals(401, refused.statusCode());
        assertEquals("invalid_client", field(refused.body(), "error"));
    }

    @Test
    void theSecretMayAlsoArriveInTheForm() throws Exception {
        String code = code(authorize(Map.of("login_hint", "shopper-4")));
        HttpResponse<String> answer = post("/token", form(Map.of(
                "grant_type", "authorization_code", "code", code, "redirect_uri", CALLBACK,
                "code_verifier", VERIFIER, "client_id", CLIENT, "client_secret", SECRET)), null);
        assertEquals(200, answer.statusCode(), answer::body);
    }

    @Test
    void refusesWhatItCannotSendBackToTheCallerAndSendsTheRestToTheRedirect() throws Exception {
        HttpResponse<String> unknownClient = get("/authorize?" + form(Map.of(
                "response_type", "code", "client_id", "someone-else", "redirect_uri", CALLBACK,
                "code_challenge", StandInProvider.s256(VERIFIER), "code_challenge_method", "S256")));
        assertEquals(400, unknownClient.statusCode());

        HttpResponse<String> noPkce = get("/authorize?" + form(Map.of(
                "response_type", "code", "client_id", CLIENT, "redirect_uri", CALLBACK, "state", "s")));
        assertEquals(302, noPkce.statusCode());
        assertEquals(CALLBACK + "?error=invalid_request&state=s", noPkce.headers().firstValue("Location").orElseThrow());

        HttpResponse<String> oddName = get("/authorize?" + form(Map.of(
                "response_type", "code", "client_id", CLIENT, "redirect_uri", CALLBACK, "login_hint", "\"}, {",
                "code_challenge", StandInProvider.s256(VERIFIER), "code_challenge_method", "S256")));
        assertTrue(oddName.headers().firstValue("Location").orElseThrow().contains("error=invalid_request"));
    }

    @Test
    void publishesWhereEverythingIs() throws Exception {
        HttpResponse<String> discovery = get("/.well-known/openid-configuration");
        assertEquals(200, discovery.statusCode());
        assertEquals(provider.issuer(), field(discovery.body(), "issuer"));
        assertEquals(provider.issuer() + "/token", field(discovery.body(), "token_endpoint"));
        assertEquals(200, get("/health").statusCode());
    }

    // --- helpers ------------------------------------------------------------------------------

    private URI authorize(Map<String, String> extra) throws Exception {
        Map<String, String> query = new java.util.LinkedHashMap<>(Map.of(
                "response_type", "code",
                "client_id", CLIENT,
                "redirect_uri", CALLBACK,
                "scope", "openid profile email",
                "code_challenge", StandInProvider.s256(VERIFIER),
                "code_challenge_method", "S256"));
        query.putAll(extra);
        HttpResponse<String> response = get("/authorize?" + form(query));
        assertEquals(302, response.statusCode(), response::body);
        return URI.create(response.headers().firstValue("Location").orElseThrow());
    }

    private static String code(URI callback) {
        return StandInProvider.parameters(callback.getRawQuery()).get("code");
    }

    private HttpResponse<String> redeem(String code, String verifier, String authorization) throws Exception {
        return post("/token", form(Map.of(
                "grant_type", "authorization_code", "code", code, "redirect_uri", CALLBACK, "code_verifier", verifier)),
                authorization);
    }

    private PublicKey publishedKey() throws Exception {
        String keys = get("/certs").body();
        RSAPublicKeySpec spec = new RSAPublicKeySpec(
                new BigInteger(1, Base64.getUrlDecoder().decode(field(keys, "n"))),
                new BigInteger(1, Base64.getUrlDecoder().decode(field(keys, "e"))));
        return KeyFactory.getInstance("RSA").generatePublic(spec);
    }

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(provider.issuer() + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String body, String authorization) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(provider.issuer() + path))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (authorization != null) {
            request.header("Authorization", authorization);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String basic(String id, String secret) {
        return "Basic " + Base64.getEncoder().encodeToString((id + ":" + secret).getBytes(StandardCharsets.UTF_8));
    }

    private static String form(Map<String, String> fields) {
        StringBuilder form = new StringBuilder();
        fields.forEach((name, value) -> form.append(form.isEmpty() ? "" : "&")
                .append(URLEncoder.encode(name, StandardCharsets.UTF_8)).append('=')
                .append(URLEncoder.encode(value, StandardCharsets.UTF_8)));
        return form.toString();
    }

    /** One string field of a flat JSON object — all these tests need, without a JSON library. */
    private static String field(String json, String name) {
        Matcher matcher = Pattern.compile("\"" + Pattern.quote(name) + "\":\"([^\"]*)\"").matcher(json);
        assertTrue(matcher.find(), () -> name + " in " + json);
        return matcher.group(1);
    }
}
