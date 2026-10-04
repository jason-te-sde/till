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
 * <p>The statements come from the same {@code db/migration} files on the classpath that Flyway
 * applies, in the same order, so there is one description of the schema rather than two that drift.
 * A service should use Flyway — it records what it applied and refuses to apply it twice — and a test
 * or an embedded use can call this instead.
 */
public final class JdbcSchema {

    /**
     * Where the schema lives on the classpath: every migration, in the order Flyway applies them. A
     * test holds this to the directory, so a migration cannot be added to one and not the other.
     */
    public static final List<String> RESOURCES = List.of(
            "db/migration/V1__till_schema.sql",
            "db/migration/V2__listing_indexes.sql",
            "db/migration/V3__stock_shards.sql",
            "db/migration/V4__apply_function.sql",
            "db/migration/V5__apply_durability.sql");

    private JdbcSchema() {}

    /**
     * Creates every table and index till needs, and the function that writes a decision.
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
        for (String resource : RESOURCES) {
            statements.addAll(split(read(resource)));
        }
        return statements;
    }

    /**
     * One migration file's text, as the statements in it.
     *
     * <p>A semicolon ends a statement except inside a quoted string: a single-quoted one, doubled
     * quotes and all, or a dollar-quoted one ({@code $$ ... $$}, or {@code $tag$ ... $tag$}), which is
     * how a function's body is written — its semicolons end the body's own statements, and the body
     * goes to the database whole. Both are read as PostgreSQL's documentation, sections 4.1.2.1 and
     * 4.1.2.4, describes them; the backslash escapes of an {@code E'...'} string are not.
     *
     * @param sql the file's contents
     * @return its statements, comments removed, in order
     */
    static List<String> split(String sql) {
        String text = stripComments(sql);
        List<String> statements = new ArrayList<>();
        String dollarQuote = null;
        boolean quoted = false;
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (dollarQuote != null) {
                // Only its own delimiter ends it; any other dollar sign in a body is text.
                if (text.startsWith(dollarQuote, i)) {
                    i += dollarQuote.length() - 1;
                    dollarQuote = null;
                }
            } else if (quoted) {
                // A doubled quote inside a string ends it and starts it again: the same thing.
                quoted = c != '\'';
            } else if (c == '\'') {
                quoted = true;
            } else if (c == '$' && !continuesIdentifier(text, i) && dollarTag(text, i) != null) {
                // A dollar sign right after an identifier's character is part of the identifier.
                dollarQuote = dollarTag(text, i);
                i += dollarQuote.length() - 1;
            } else if (c == ';') {
                addStatement(statements, text.substring(start, i));
                start = i + 1;
            }
        }
        addStatement(statements, text.substring(start));
        return statements;
    }

    /**
     * The dollar-quote delimiter that starts at {@code at} — {@code $$}, or a tag between two dollar
     * signs that could be an unquoted identifier — or null if the dollar sign there starts none, as
     * the one in a positional parameter does not.
     */
    private static String dollarTag(String text, int at) {
        int end = text.indexOf('$', at + 1);
        if (end < 0 || !text.substring(at + 1, end).matches("([A-Za-z_][A-Za-z0-9_]*)?")) {
            return null;
        }
        return text.substring(at, end + 1);
    }

    private static boolean continuesIdentifier(String text, int at) {
        if (at == 0) {
            return false;
        }
        char before = text.charAt(at - 1);
        return Character.isLetterOrDigit(before) || before == '_' || before == '$';
    }

    private static void addStatement(List<String> statements, String candidate) {
        String trimmed = candidate.trim();
        if (!trimmed.isEmpty()) {
            statements.add(trimmed);
        }
    }

    /**
     * Removes whole-line comments.
     *
     * <p>Before splitting, not after. A comment is prose and prose contains semicolons — one in this
     * file did — so splitting first cuts a comment in half and leaves its second half glued to the
     * front of the next statement, where it is a syntax error rather than a comment.
     *
     * <p>A whole-line comment inside a function body goes too, which changes nothing the function
     * does.
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

    private static String read(String resource) {
        ClassLoader loader = JdbcSchema.class.getClassLoader();
        try (InputStream in = loader.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException(resource + " is not on the classpath");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("could not read " + resource, e);
        }
    }
}
