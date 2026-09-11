package io.till.jdbc;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;

/**
 * Creates the tables, for callers that are not running Flyway.
 *
 * <p>The statements come from the same {@code db/migration/V1__till_schema.sql} on the classpath that
 * Flyway applies, so there is one description of the schema rather than two that drift. A service
 * should use Flyway — it records what it applied and refuses to apply it twice — and a test or an
 * embedded use can call this instead.
 */
public final class JdbcSchema {

    /** Where the schema lives on the classpath. */
    public static final String RESOURCE = "db/migration/V1__till_schema.sql";

    private JdbcSchema() {}

    /**
     * Creates every table and index till needs.
     *
     * @param dataSource where to create them
     * @throws SQLException if the database refuses a statement
     * @throws IllegalStateException if the schema resource is missing from the classpath
     */
    public static void create(DataSource dataSource) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            for (String sql : statements()) {
                statement.execute(sql);
            }
        }
    }

    /**
     * Drops every table till owns, for a test that wants a clean database without a new container.
     *
     * @param dataSource where to drop them
     * @throws SQLException if the database refuses a statement
     */
    public static void drop(DataSource dataSource) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute(
                    "drop table if exists till_outbox, till_idempotency, till_reservation_line, "
                            + "till_reservation, till_stock cascade");
        }
    }

    /**
     * The schema, split into statements.
     *
     * @return the statements, in the order they must run
     */
    static List<String> statements() {
        List<String> statements = new ArrayList<>();
        for (String candidate : stripComments(read()).split(";")) {
            String trimmed = candidate.trim();
            if (!trimmed.isEmpty()) {
                statements.add(trimmed);
            }
        }
        return statements;
    }

    /**
     * Removes whole-line comments.
     *
     * <p>Before splitting, not after. A comment is prose and prose contains semicolons — one in this
     * file did — so splitting first cuts a comment in half and leaves its second half glued to the
     * front of the next statement, where it is a syntax error rather than a comment.
     *
     * <p>Splitting on a semicolon at all is only safe because the schema has no function bodies, no
     * triggers and no string literals. If it ever does, this has to change with it, and a test
     * asserting the current behaviour is what will notice.
     */
    private static String stripComments(String sql) {
        StringBuilder sb = new StringBuilder(sql.length());
        for (String line : sql.split("\n")) {
            if (!line.strip().startsWith("--")) {
                sb.append(line).append('\n');
            }
        }
        return sb.toString();
    }

    private static String read() {
        ClassLoader loader = JdbcSchema.class.getClassLoader();
        try (InputStream in = loader.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(RESOURCE + " is not on the classpath");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("could not read " + RESOURCE, e);
        }
    }
}
