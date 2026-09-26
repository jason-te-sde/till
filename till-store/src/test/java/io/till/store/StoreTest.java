package io.till.store;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;

import io.till.store.events.Projector;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The whole store, against a real PostgreSQL and a real Redis, with the real reservation kernel in
 * memory standing in for the ledger service.
 *
 * <p>Containers are started once per test JVM and shared: a context is cached across classes anyway,
 * and a container per class would multiply start-up by the number of classes for isolation that a
 * {@code truncate} already gives. Every test starts from an empty ledger, an empty set of orders and
 * the clock at {@link #T0}.
 *
 * <p>Most tests sign in with Spring Security's {@code oidcLogin()}, which puts an OIDC principal on
 * the request without the round trip to a provider — the right shortcut for a test about orders. The
 * round trip itself is {@link SignInTest}'s subject, against {@link FakeIdentityProvider}, which every
 * test's configuration points at so that there is one context to cache rather than two; and again
 * against Keycloak in the browser suite.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(StoreTest.Embedded.class)
// Classes run in parallel across the build, and these share one database and — through the cached
// context — one clock and one ledger that every test resets. Two of them at once would truncate each
// other's tables and move each other's clock, so they take turns; everything else still runs alongside.
@ResourceLock("till-store")
public abstract class StoreTest {

    /** The instant every test starts at. */
    public static final Instant T0 = Instant.parse("2026-09-18T12:00:00Z");

    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    @SuppressWarnings("resource")
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    /** Contacted only by tests that sign in the long way; see {@link SignInTest}. */
    static final FakeIdentityProvider PROVIDER = FakeIdentityProvider.start();

    static {
        Startables.deepStart(POSTGRES, REDIS).join();
    }

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        // The explicit form, as the local stack uses: nothing is fetched at start-up.
        registry.add("store.auth.oidc.issuer-uri", PROVIDER::issuer);
        registry.add("store.auth.oidc.client-id", () -> FakeIdentityProvider.CLIENT_ID);
        registry.add("store.auth.oidc.client-secret", () -> FakeIdentityProvider.CLIENT_SECRET);
        registry.add("store.auth.oidc.authorization-uri", PROVIDER::authorizationUri);
        registry.add("store.auth.oidc.token-uri", PROVIDER::tokenUri);
        registry.add("store.auth.oidc.jwk-set-uri", PROVIDER::jwkSetUri);
        registry.add(
                "store.auth.oidc.logout-uri",
                () -> PROVIDER.logoutUri() + "?client_id={clientId}&post_logout_redirect_uri={baseUrl}/&id_token_hint={idToken}");
    }

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected MutableClock clock;

    @Autowired
    protected EmbeddedLedger ledger;

    @Autowired
    protected Projector projector;

    @Autowired
    protected JdbcClient jdbc;

    @BeforeEach
    void freshStart() {
        clock.reset(T0);
        ledger.reset();
        // Everything except the catalogue, which the migrations seeded and no test changes.
        jdbc.sql("truncate store_order_line, store_order, store_sales_daily, store_availability, store_consumed_event")
                .update();
    }

    /**
     * @param subject who
     * @return a signed-in customer
     */
    protected static RequestPostProcessor customer(String subject) {
        return oidcLogin()
                .idToken(token -> token.subject(subject).claim("name", "Player " + subject).claim("email", subject + "@example.test"))
                .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"));
    }

    /**
     * @param subject who
     * @return a signed-in member of the admin group
     */
    protected static RequestPostProcessor operator(String subject) {
        return oidcLogin()
                .idToken(token -> token.subject(subject).claim("name", "Operator " + subject))
                .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"), new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    /**
     * The CSRF double submit, exactly as the SPA performs it: one value in the {@code XSRF-TOKEN}
     * cookie and in the {@code X-XSRF-TOKEN} header.
     *
     * <p>Spring Security's own {@code csrf()} shortcut is used nowhere in these tests. It works by
     * swapping the token repository inside the filter chain for a session-based one — and the chain
     * belongs to the cached context, so the swap outlives the test that made it and quietly turns every
     * later test's cookie-based CSRF into something production never runs.
     *
     * @return the post-processor
     */
    protected static RequestPostProcessor csrfToken() {
        return request -> {
            String token = "test-csrf-token";
            Cookie[] existing = request.getCookies() == null ? new Cookie[0] : request.getCookies();
            Cookie[] cookies = Arrays.copyOf(existing, existing.length + 1);
            cookies[existing.length] = new Cookie("XSRF-TOKEN", token);
            request.setCookies(cookies);
            request.addHeader("X-XSRF-TOKEN", token);
            return request;
        };
    }

    /**
     * @return a fresh idempotency key
     */
    protected static String key() {
        return UUID.randomUUID().toString();
    }

    /** The embedded ledger and the test clock, replacing the real ones. */
    @TestConfiguration
    static class Embedded {

        @Bean
        MutableClock testClock() {
            return new MutableClock(T0);
        }

        @Bean
        @Primary
        java.time.Clock clockForTheStore(MutableClock clock) {
            return clock;
        }

        /**
         * One bean, primary for both of the store's ports. Declaring an alias for each as well would make
         * three primary candidates — the ledger itself implements both interfaces — and Spring rightly
         * refuses to choose.
         */
        @Bean
        @Primary
        EmbeddedLedger embeddedLedger(MutableClock clock) {
            return new EmbeddedLedger(clock);
        }
    }
}
