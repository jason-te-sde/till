package io.till.server;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/**
 * pgjdbc's login timeout, which bounds how long <em>opening a brand-new connection</em> may take —
 * separately from {@code connection-timeout}, which bounds how long a caller waits for one already
 * in the pool.
 *
 * <p>Before {@code spring.datasource.hikari.data-source-properties.loginTimeout} existed, nothing
 * here bounded a new connection's login at all. Hikari does try to communicate a bound derived from
 * {@code connection-timeout} to the driver ({@code PoolBase.setLoginTimeout}, which calls {@code
 * dataSource.setLoginTimeout(Math.max(1, toSeconds(500 + connectionTimeout)))}), but for a {@code
 * jdbcUrl}-configured pool that call only reaches {@code DriverManager.setLoginTimeout(int)} — a
 * global the pool's own {@code DriverDataSource} sets but pgjdbc's {@code Driver.connect} never
 * reads back, because {@code PGProperty.LOGIN_TIMEOUT} carries its own default of {@code "0"}, which
 * {@code Driver.timeout(Properties)} finds before it would ever fall back to the global (verified by
 * reading {@code org.postgresql.Driver} and {@code PGProperty} at this exact version: a slow login
 * below was found to complete rather than fail, with or without Hikari's global set). So a login
 * that hangs, rather than merely being slow, could block a pool-filling thread forever; setting
 * {@code loginTimeout} as a data source property is what gives the driver a bound of its own,
 * through the property {@code Driver.timeout(Properties)} reads first.
 *
 * <p>{@link #aLoginTimeoutShorterThanTheDelayFailsWithinIt()} proves the property is actually wired
 * through end to end: overriding it to one second, the same way {@code TILL_DB_LOGIN_TIMEOUT} would,
 * turns a login slower than that into a prompt failure instead of an eventual success. {@link
 * #opensAConnectionDespiteASlowLogin()} is the scenario the load test actually hit: the configured
 * default is generous enough that the pool still opens a connection when the database takes about
 * three seconds to accept one (docs/load-test.md).
 *
 * <p>Both are proved against a login that is genuinely slow, not a network-level stand-in for one: a
 * PostgreSQL 17 {@code on login} event trigger, installed on a database created just for this test so
 * that no other suite sharing {@link TestDatabase}'s server is slowed by it.
 */
@ExtendWith(RequiresDatabase.class)
class LoginTimeoutTest {

    private static final String SLOW_LOGIN_DATABASE = "till_test_login_timeout";

    private String serverUrl;

    @BeforeEach
    void createSlowLoginDatabase() throws SQLException {
        serverUrl = TestDatabase.url();
        try (Connection connection =
                        DriverManager.getConnection(serverUrl, TestDatabase.username(), TestDatabase.password());
                Statement statement = connection.createStatement()) {
            // WITH (FORCE): the same reason TestDatabase drops its own database that way — a
            // connection arriving in the window between terminating sessions and dropping makes a
            // two-statement version flaky rather than wrong.
            statement.execute("drop database if exists " + SLOW_LOGIN_DATABASE + " with (force)");
            statement.execute("create database " + SLOW_LOGIN_DATABASE);
        }
    }

    @AfterEach
    void dropSlowLoginDatabase() throws SQLException {
        // Connects to a different database on the same server, so dropping this one never has to
        // wait on whatever trigger it carries.
        try (Connection connection =
                        DriverManager.getConnection(serverUrl, TestDatabase.username(), TestDatabase.password());
                Statement statement = connection.createStatement()) {
            statement.execute("drop database if exists " + SLOW_LOGIN_DATABASE + " with (force)");
        }
    }

    @Test
    @DisplayName("a pool configured with the application's settings still opens a connection when login takes about 3s")
    void opensAConnectionDespiteASlowLogin() throws SQLException, IOException {
        installSlowLoginTrigger(3);
        HikariConfig config = hikariConfigFromApplicationYml(Map.of());
        pointAt(config, "till-login-timeout-test");

        Instant started = Instant.now();
        assertDoesNotThrow(
                () -> {
                    try (HikariDataSource dataSource = new HikariDataSource(config);
                            Connection connection = dataSource.getConnection()) {
                        assertTrue(connection.isValid(2));
                    }
                },
                "a connection should open even though the login takes about 3s, because loginTimeout allows 10");
        Duration elapsed = Duration.between(started, Instant.now());
        assertTrue(
                elapsed.compareTo(Duration.ofMillis(2500)) >= 0,
                () -> "expected the login trigger to have actually slowed this down, took " + elapsed);
    }

    @Test
    @DisplayName("TILL_DB_LOGIN_TIMEOUT genuinely bounds the login: shorter than the delay, it fails instead of waiting it out")
    void aLoginTimeoutShorterThanTheDelayFailsWithinIt() throws SQLException, IOException {
        installSlowLoginTrigger(5);
        // The same override an operator would make with the environment variable, layered over the
        // packaged application.yml exactly the way an environment variable outranks it in a real
        // deployment (TestDatabase's own JVM is not restarted between tests, so this cannot be set
        // as a real environment variable here; the override property source stands in for it).
        HikariConfig config = hikariConfigFromApplicationYml(Map.of("TILL_DB_LOGIN_TIMEOUT", "1"));
        pointAt(config, "till-login-timeout-override-test");

        Instant started = Instant.now();
        assertThrows(
                RuntimeException.class,
                () -> new HikariDataSource(config).close(),
                "a one-second login timeout should give up long before a five-second login finishes");
        Duration elapsed = Duration.between(started, Instant.now());
        assertTrue(
                elapsed.compareTo(Duration.ofMillis(3500)) < 0,
                () -> "expected the override to cut this off well before the 5s login would have finished, took "
                        + elapsed);
    }

    private void installSlowLoginTrigger(int sleepSeconds) throws SQLException {
        try (Connection connection =
                        DriverManager.getConnection(
                                TestDatabase.withDatabase(serverUrl, SLOW_LOGIN_DATABASE),
                                TestDatabase.username(),
                                TestDatabase.password());
                Statement statement = connection.createStatement()) {
            // Event triggers are per-database, so this slows only logins to this one database —
            // nothing else on the shared server, including every other suite's connections to
            // TestDatabase's own database, is affected.
            statement.execute(
                    "create function till_test_slow_login() returns event_trigger "
                            + "language plpgsql as $$ begin perform pg_sleep(" + sleepSeconds + "); end; $$");
            statement.execute(
                    "create event trigger till_test_slow_login on login execute function till_test_slow_login()");
        }
    }

    private void pointAt(HikariConfig config, String poolName) {
        config.setJdbcUrl(TestDatabase.withDatabase(serverUrl, SLOW_LOGIN_DATABASE));
        config.setUsername(TestDatabase.username());
        config.setPassword(TestDatabase.password());
        // Cosmetic only: keeps this pool's log lines and Hikari's housekeeper thread name apart
        // from the one every API test's Spring context runs in the same JVM.
        config.setPoolName(poolName);
    }

    /**
     * Binds {@code spring.datasource.hikari.*} from this module's real {@code application.yml} onto
     * a fresh {@link HikariConfig} — the same property names, defaults and placeholder resolution
     * Spring Boot itself would use, so the pool under test is configured exactly as the running
     * service is rather than by values copied into this test by hand.
     *
     * @param environmentOverrides stand in for environment variables such as {@code
     *     TILL_DB_LOGIN_TIMEOUT}: layered with higher precedence than the packaged file, the same
     *     relationship a real environment variable has to it
     */
    private static HikariConfig hikariConfigFromApplicationYml(Map<String, String> environmentOverrides)
            throws IOException {
        List<PropertySource<?>> loaded =
                new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"));
        MutablePropertySources propertySources = new MutablePropertySources();
        loaded.forEach(propertySources::addLast);
        if (!environmentOverrides.isEmpty()) {
            propertySources.addFirst(new MapPropertySource("test-environment", Map.copyOf(environmentOverrides)));
        }
        Binder binder =
                new Binder(
                        ConfigurationPropertySources.from(propertySources),
                        new PropertySourcesPlaceholdersResolver(propertySources));
        HikariConfig config = new HikariConfig();
        binder.bind("spring.datasource.hikari", Bindable.ofInstance(config));
        return config;
    }
}
