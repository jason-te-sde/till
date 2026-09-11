package io.till.server;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Where the PostgreSQL-backed suites get a database.
 *
 * <p>Two ways of finding a <i>server</i>, in this order:
 *
 * <ul>
 *   <li>{@code TILL_TEST_DB_URL} — one that already exists. What CI uses, through a service
 *       container, and what somebody who already has PostgreSQL installed can use without Docker.
 *   <li>Testcontainers — started once for the JVM and shared by every class in the module.
 * </ul>
 *
 * <p>Then it creates a database of its own on that server, named after this module, dropping any
 * previous one. That last part is not tidiness. This module manages its schema with
 * {@link io.till.jdbc.JdbcSchema} and the other one manages it with Flyway, so two modules pointed at one
 * database leave each other a schema the other does not expect — and the failure surfaces as a
 * missing table three suites later rather than as a clash here. A shared server is fine; a shared
 * database is not, and the environment variable naming a server rather than a database is what makes
 * that impossible to get wrong.
 *
 * <p>Duplicated between {@code till-jdbc} and {@code till-server} on purpose: sharing it would mean
 * publishing a test jar from one module and depending on it from the other, which is a worse trade
 * than fifty lines.
 */
final class TestDatabase {

    /** Set this to a PostgreSQL server of your own instead of letting Docker start one. */
    static final String URL_ENV = "TILL_TEST_DB_URL";

    /** The database this module creates for itself. */
    private static final String DATABASE = "till_test_server";

    private static String url;
    private static String username;
    private static String password;

    private TestDatabase() {}

    /**
     * Whether a server can be reached at all.
     *
     * @return true if the environment names one, or Docker can start one
     */
    static synchronized boolean isAvailable() {
        return System.getenv(URL_ENV) != null || DockerClientFactory.instance().isDockerAvailable();
    }

    static synchronized String url() {
        start();
        return url;
    }

    static synchronized String username() {
        start();
        return username;
    }

    static synchronized String password() {
        start();
        return password;
    }

    private static void start() {
        if (url != null) {
            return;
        }
        String serverUrl;
        String fromEnvironment = System.getenv(URL_ENV);
        if (fromEnvironment != null) {
            serverUrl = fromEnvironment;
            username = orElse("TILL_TEST_DB_USER", "till");
            password = orElse("TILL_TEST_DB_PASSWORD", "till");
        } else {
            PostgreSQLContainer container = new PostgreSQLContainer("postgres:17-alpine");
            container.start();
            serverUrl = container.getJdbcUrl();
            username = container.getUsername();
            password = container.getPassword();
            Runtime.getRuntime().addShutdownHook(new Thread(container::stop));
        }
        recreate(serverUrl);
        url = withDatabase(serverUrl, DATABASE);
    }

    /** Drops and creates this module's database on the server the URL points at. */
    private static void recreate(String serverUrl) {
        try (Connection connection = DriverManager.getConnection(serverUrl, username, password);
                Statement statement = connection.createStatement()) {
            // WITH (FORCE) rather than terminating the sessions first and then dropping. The two
            // statement version has a window between them, and a connection arriving in it makes
            // the drop fail with "is being accessed by other users" — which is a flaky suite rather
            // than a real failure. PostgreSQL 13 and later do both atomically.
            statement.execute("drop database if exists " + DATABASE + " with (force)");
            statement.execute("create database " + DATABASE);
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "could not create the test database " + DATABASE + " on " + serverUrl
                            + "; " + URL_ENV + " must name a server whose user may create databases",
                    e);
        }
    }

    /** Replaces the database in a JDBC URL, keeping the host, the port and any parameters. */
    static String withDatabase(String jdbcUrl, String database) {
        int lastSlash = jdbcUrl.lastIndexOf('/');
        if (lastSlash < 0) {
            throw new IllegalArgumentException("not a JDBC URL with a database in it: " + jdbcUrl);
        }
        int query = jdbcUrl.indexOf('?', lastSlash);
        String parameters = query < 0 ? "" : jdbcUrl.substring(query);
        return jdbcUrl.substring(0, lastSlash + 1) + database + parameters;
    }

    private static String orElse(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
