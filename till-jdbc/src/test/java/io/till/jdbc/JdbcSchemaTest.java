package io.till.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
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
                    statement.startsWith("create"),
                    "a statement that is not a create: " + statement.lines().findFirst().orElse(""));
            assertFalse(statement.contains("--"), "a comment survived into: " + statement);
        }
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
