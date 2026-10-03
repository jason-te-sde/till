package io.till.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The schema file, read without a database in sight. */
class JdbcSchemaTest {

    @Test
    @DisplayName("every table and index the adapter needs is in the file")
    void theSchemaIsComplete() {
        String sql = String.join("\n", JdbcSchema.statements());

        for (String table :
                List.of(
                        "till_stock",
                        "till_reservation",
                        "till_reservation_line",
                        "till_idempotency",
                        "till_outbox")) {
            assertTrue(sql.contains("create table " + table), table + " is missing from the schema");
        }
        assertTrue(sql.contains("till_reservation_line_sku"), "the reclaim path needs an index on sku");
        assertTrue(sql.contains("till_outbox_unpublished"), "the publisher needs an index on the tail");
    }

    @Test
    @DisplayName("the oversell guard is a database constraint, not only a Java one")
    void theConstraintIsInTheSchema() {
        String sql = String.join("\n", JdbcSchema.statements());

        assertTrue(sql.contains("reserved <= on_hand"), "the check constraint is what makes an oversell impossible");
    }

    @Test
    @DisplayName("a comment containing a semicolon does not cut a statement in half")
    void commentsAreStrippedBeforeSplitting() {
        // The schema really does contain such a comment, and splitting on ';' before removing
        // comments left its second half glued to the front of the next statement. Every statement
        // starting with 'create' is the assertion that no longer happens.
        List<String> statements = JdbcSchema.statements();

        assertFalse(statements.isEmpty());
        for (String statement : statements) {
            assertTrue(
                    statement.startsWith("create") || statement.startsWith("alter"),
                    "a statement that is not a create or an alter: " + statement.lines().findFirst().orElse(""));
            assertFalse(statement.contains("--"), "a comment survived into: " + statement);
        }
    }

    @Test
    @DisplayName("a function body's semicolons do not end the statement that creates it")
    void aFunctionBodyStaysWhole() {
        List<String> statements = JdbcSchema.split(
                """
                create function f() returns void language plpgsql as $$
                begin
                    perform 1;
                    perform 2;
                end
                $$;
                create function g() returns text language sql as $body$ select 'a;b' $body$;
                create table t (x integer);
                """);

        assertEquals(3, statements.size(), "three statements, got " + statements);
        assertTrue(statements.get(0).startsWith("create function f()"), statements.get(0));
        assertTrue(statements.get(0).endsWith("end\n$$"), "the body arrives whole: " + statements.get(0));
        assertEquals("create function g() returns text language sql as $body$ select 'a;b' $body$", statements.get(1));
        assertEquals("create table t (x integer)", statements.get(2));
    }

    @Test
    @DisplayName("a dollar quote closes at its own delimiter, even where another tag runs into it")
    void aDollarQuoteClosesAtItsOwnDelimiter() {
        // Inside $$, "$x$$" is the text "$x" and then the closing $$, as PostgreSQL reads it: a
        // delimiter that is not the closing one gives its last dollar sign back.
        List<String> statements =
                JdbcSchema.split("create function f() returns text language sql as $$ select 'a$x$$;\n"
                        + "create table t (x integer);");

        assertEquals(
                List.of("create function f() returns text language sql as $$ select 'a$x$$", "create table t (x integer)"),
                statements,
                "two statements, got " + statements);
    }

    @Test
    @DisplayName("a semicolon or a dollar sign inside a quoted string does not end or open anything")
    void aQuotedStringIsText() {
        List<String> statements =
                JdbcSchema.split("create table t (x text default 'a;$$b''c');\ncreate table u (y integer);");

        assertEquals(
                List.of("create table t (x text default 'a;$$b''c')", "create table u (y integer)"),
                statements,
                "two statements, got " + statements);
    }

    @Test
    @DisplayName("a dollar sign inside an identifier does not start a quote")
    void aDollarInAnIdentifierIsNotAQuote() {
        // PostgreSQL allows $ after an identifier's first character, and a dollar quote that follows
        // one has to be separated from it by whitespace; so "a$b$c" is a name, not a quoted "b".
        List<String> statements = JdbcSchema.split("create table t (a$b$c integer);\ncreate table u (x integer);");

        assertEquals(
                List.of("create table t (a$b$c integer)", "create table u (x integer)"),
                statements,
                "two statements, got " + statements);
    }

    @Test
    @DisplayName("every migration Flyway would apply is applied here too, in the same order")
    void everyMigration() throws Exception {
        List<String> files;
        try (Stream<Path> listing = Files.list(Path.of("src/main/resources/db/migration"))) {
            files = listing.map(path -> "db/migration/" + path.getFileName())
                    .filter(name -> name.matches("db/migration/V\\d+__.*\\.sql"))
                    .sorted(Comparator.comparingInt(name -> Integer.parseInt(name.replaceAll("db/migration/V(\\d+)__.*", "$1"))))
                    .toList();
        }
        assertEquals(files, JdbcSchema.RESOURCES);
    }

    @Test
    @DisplayName("the statements are in dependency order, so they can simply be run")
    void orderIsUsable() {
        List<String> statements = JdbcSchema.statements();

        int reservation = indexOf(statements, "create table till_reservation ");
        int lines = indexOf(statements, "create table till_reservation_line");
        assertTrue(reservation < lines, "the line table has a foreign key to the reservation table");
    }

    private static int indexOf(List<String> statements, String prefix) {
        for (int i = 0; i < statements.size(); i++) {
            if (statements.get(i).startsWith(prefix)) {
                return i;
            }
        }
        throw new AssertionError("no statement starting with '" + prefix + "'");
    }
}
