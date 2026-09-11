package io.till.server;

import io.till.client.TillClient;
import io.till.jdbc.JdbcLedger;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

/**
 * The whole service, on a real PostgreSQL, driven over HTTP by the real client.
 *
 * <p>Everything below the HTTP layer is covered by faster suites; what only this can reach is the
 * wiring. Flyway actually applying the schema, Spring actually binding the properties, the auth
 * filter actually running before the controller, an {@code Instant} actually surviving Jackson and
 * {@code timestamptz} and coming back equal to the one that went out.
 *
 * <p>One database for the whole module, truncated between tests, from wherever {@link TestDatabase}
 * finds one. A container per class would multiply a two-second startup by the number of classes for
 * isolation that a {@code truncate} already gives.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ExtendWith(RequiresDatabase.class)
@Import(ApiTestBase.Clocks.class)
@TestPropertySource(
        properties = {
            "till.auth.client-token=client-secret",
            "till.auth.admin-token=admin-secret",
            // Driven by hand in the tests that are about them, so no test ever waits for a
            // scheduler to come round.
            "till.sweeper.enabled=false",
            "till.outbox.enabled=false",
            // In production the management endpoints are on a port of their own, so that metrics
            // and probes are not reachable from wherever the API is. In a test they share the
            // random port, because binding a fixed one makes two test JVMs collide.
            "management.server.port="
        })
abstract class ApiTestBase {

    static final String CLIENT_TOKEN = "client-secret";
    static final String ADMIN_TOKEN = "admin-secret";
    static final Instant T0 = Instant.parse("2026-09-10T12:00:00Z");

    /**
     * Points Spring at whatever database {@link TestDatabase} found.
     *
     * <p>Not {@code @ServiceConnection} on a container field, because that hard-wires the suite to
     * Docker. This way the same tests run against a local PostgreSQL, a service container in CI, or
     * a container started here, and nothing in them knows which.
     *
     * @param registry Spring's late-bound property source
     */
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", TestDatabase::url);
        registry.add("spring.datasource.username", TestDatabase::username);
        registry.add("spring.datasource.password", TestDatabase::password);
    }

    @LocalServerPort
    int port;

    @Autowired
    MutableClock clock;

    @Autowired
    JdbcLedger ledger;

    @Autowired
    DataSource dataSource;

    @BeforeEach
    void resetDatabaseAndClock() throws SQLException {
        try (var connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute(
                    "truncate till_outbox, till_idempotency, till_reservation_line, till_reservation, till_stock "
                            + "restart identity cascade");
        }
        // The context, and so the clock, is shared across the whole suite: a test that moved time
        // forward would otherwise leave every hold in the next one already expired.
        clock.reset(T0);
    }

    /**
     * A client with the ordinary token.
     *
     * @return the client
     */
    TillClient client() {
        return client(CLIENT_TOKEN);
    }

    /**
     * A client with the admin token, for the calls that change stock levels.
     *
     * @return the client
     */
    TillClient admin() {
        return client(ADMIN_TOKEN);
    }

    /**
     * A client with a token of your choosing.
     *
     * @param token the bearer token, or null for none
     * @return the client
     */
    TillClient client(String token) {
        // One attempt: a test that is about a 503 should see the 503 rather than the client's
        // retry of it, and nothing here is contended enough to produce one by accident.
        return TillClient.builder("http://127.0.0.1:" + port).token(token).maxAttempts(1).build();
    }

    /** Replaces the clock with one the tests move. */
    @TestConfiguration
    static class Clocks {

        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock(T0);
        }
    }
}
