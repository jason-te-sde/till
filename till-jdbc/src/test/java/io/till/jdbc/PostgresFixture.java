package io.till.jdbc;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.SQLException;
import javax.sql.DataSource;

/**
 * One pool against one database for the whole module, with the tables truncated between tests.
 *
 * <p>Shared because standing a database up costs a second or two and correctness does not depend on
 * having a fresh one; truncated between tests because sharing rows between them does.
 *
 * <p>Not the Testcontainers JUnit extension, which would start a container per class and multiply
 * that cost by the number of classes for isolation that {@link #reset} already gives. Where the
 * database comes from is {@link TestDatabase}'s business.
 */
final class PostgresFixture {

    private static HikariDataSource dataSource;

    private PostgresFixture() {}

    static synchronized DataSource dataSource() {
        if (dataSource != null) {
            return dataSource;
        }
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(TestDatabase.url());
        config.setUsername(TestDatabase.username());
        config.setPassword(TestDatabase.password());
        // Above the widest concurrency test, so that a thread waiting for a connection is never
        // mistaken for a thread waiting for a lock.
        config.setMaximumPoolSize(24);
        config.setPoolName("till-test");
        HikariDataSource pool = new HikariDataSource(config);

        try {
            // Dropped first, so that a run against somebody's own database starts from the schema
            // this build expects rather than from whatever the last run left behind.
            JdbcSchema.drop(pool);
            JdbcSchema.create(pool);
        } catch (SQLException e) {
            pool.close();
            throw new IllegalStateException("could not create the schema", e);
        }
        // Assigned last, and this is the point: assigning it before the schema existed meant a
        // failure here was reported once and then hidden, because every later call found a non-null
        // field and returned a pool pointing at half a schema.
        dataSource = pool;
        Runtime.getRuntime().addShutdownHook(new Thread(PostgresFixture::close));
        return dataSource;
    }

    static void reset() {
        try (var connection = dataSource().getConnection();
                var statement = connection.createStatement()) {
            statement.execute(
                    "truncate till_outbox, till_idempotency, till_reservation_line, till_reservation, till_stock "
                            + "restart identity cascade");
        } catch (SQLException e) {
            throw new IllegalStateException("could not reset the database", e);
        }
    }

    private static synchronized void close() {
        if (dataSource != null) {
            dataSource.close();
            dataSource = null;
        }
    }
}
