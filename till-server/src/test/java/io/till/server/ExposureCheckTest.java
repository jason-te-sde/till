package io.till.server;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The check that keeps an unauthenticated inventory ledger off the network.
 *
 * <p>A unit test rather than a context test, because what is being asserted is that startup
 * <i>fails</i>, and a Spring test that asserts a context cannot be created is slower and says less.
 */
class ExposureCheckTest {

    @Test
    @DisplayName("no token and every interface is a refusal to start")
    void refusesToStartOpenToTheWorld() {
        IllegalStateException thrown =
                assertThrows(IllegalStateException.class, () -> check("", null, null, false));

        assertTrue(thrown.getMessage().contains("client-token"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("insecure"), thrown.getMessage());
    }

    @Test
    @DisplayName("no token and a routable address is a refusal to start")
    void refusesToStartOnARoutableAddress() {
        assertThrows(IllegalStateException.class, () -> check("10.0.1.7", null, null, false));
    }

    @ParameterizedTest
    @ValueSource(strings = {"127.0.0.1", "::1", "localhost"})
    @DisplayName("no token on loopback is fine, because a laptop should be one command")
    void allowsLoopbackWithoutAToken(String address) {
        assertDoesNotThrow(() -> check(address, null, null, false));
    }

    @Test
    @DisplayName("a token makes any address fine")
    void aTokenIsEnough() {
        assertDoesNotThrow(() -> check("", "secret", "admin", false));
        assertDoesNotThrow(() -> check("10.0.1.7", "secret", "admin", false));
    }

    @Test
    @DisplayName("insecure is the escape hatch, and it has to be asked for by that name")
    void insecureIsTheEscapeHatch() {
        assertDoesNotThrow(() -> check("", null, null, true));
    }

    @Test
    @DisplayName("management endpoints on the API's own port are a warning, not a refusal")
    void warnsWhenManagementSharesThePort() {
        TillProperties properties = properties("secret", "admin", false);

        // A warning rather than a refusal: it is the right configuration for a test, and the tests
        // in this repository use it. What matters is that it starts and says so rather than leaving
        // /actuator/prometheus quietly readable by anyone who can reach the service.
        assertDoesNotThrow(() -> new ExposureCheck(properties, "", "8080", "8080").check());
        assertDoesNotThrow(() -> new ExposureCheck(properties, "", "8080", "").check());
    }

    @Test
    @DisplayName("a wildcard CORS origin is refused, because an inventory API is not a public one")
    void refusesWildcardCors() {
        assertThrows(IllegalArgumentException.class, () -> new TillProperties.Web(List.of("*")));
        assertDoesNotThrow(() -> new TillProperties.Web(List.of("https://ops.example.com")));
    }

    @Test
    @DisplayName("a client token with no admin token starts, having said what that means")
    void oneTokenIsAllowedAndWarnedAbout() {
        assertDoesNotThrow(() -> check("", "secret", null, false));
    }

    private static void check(String address, String clientToken, String adminToken, boolean insecure) {
        new ExposureCheck(properties(clientToken, adminToken, insecure), address, "8080", "9101").check();
    }

    private static TillProperties properties(String clientToken, String adminToken, boolean insecure) {
        return new TillProperties(
                8,
                32,
                Duration.ofMinutes(15),
                Duration.ofHours(24),
                insecure,
                new TillProperties.Auth(clientToken, adminToken),
                new TillProperties.Sweeper(true, Duration.ofSeconds(5), 200),
                new TillProperties.Outbox(true, Duration.ofSeconds(1), 200),
                new TillProperties.Web(List.of()),
                new TillProperties.RetentionPolicy(
                        true,
                        Duration.ofHours(1),
                        1000,
                        20,
                        Duration.ofDays(7),
                        Duration.ofDays(30),
                        Duration.ZERO));
    }
}
