package io.till.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

/**
 * The migration actually ran.
 *
 * <p>This test exists because it did not, for a while, and everything was green. Spring Boot 4 moved
 * Flyway's auto-configuration into a module of its own, so {@code flyway-core} on the classpath
 * stopped being enough to make migrations run — and nothing said so. The suite kept passing because
 * another module's test fixture had created identically named tables in the same database, so the
 * service found the schema it needed and never noticed it had not built it.
 *
 * <p>Two mistakes hiding each other, and the only thing that would have caught either is asserting
 * that the thing which is supposed to have run has left its mark.
 */
// Suites that share the one database run one at a time; see the note in the other API tests.
@ResourceLock("till-database")
class MigrationTest extends ApiTestBase {

    @Test
    @DisplayName("Flyway ran, and recorded that it did")
    void flywayRecordedTheMigration() throws SQLException {
        List<String> applied = new ArrayList<>();
        try (var connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet rows =
                        statement.executeQuery(
                                "select version, description, success from flyway_schema_history order by installed_rank")) {
            while (rows.next()) {
                assertTrue(rows.getBoolean("success"), "migration " + rows.getString("version") + " failed");
                applied.add(rows.getString("version") + " " + rows.getString("description"));
            }
        }

        assertEquals(
                List.of("1 till schema", "2 listing indexes"),
                applied,
                "the schema history is not what this build ships");
    }

    @Test
    @DisplayName("the listing indexes exist, because without them both listings are sequential scans")
    void theListingIndexesExist() throws SQLException {
        List<String> indexes = new ArrayList<>();
        try (var connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet rows =
                        statement.executeQuery(
                                "select indexname from pg_indexes where schemaname = 'public' "
                                        + "and indexname in ('till_stock_sku_c', 'till_reservation_by_state')")) {
            while (rows.next()) {
                indexes.add(rows.getString("indexname"));
            }
        }

        // Named individually: an index that quietly stops being created turns a paged listing into a
        // full sort, which nothing else in this suite would notice on a table with ten rows in it.
        assertEquals(List.of("till_reservation_by_state", "till_stock_sku_c"), indexes.stream().sorted().toList());
    }

    @Test
    @DisplayName("and the constraint that makes an oversell impossible came with it")
    void theCheckConstraintExists() throws SQLException {
        try (var connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet rows =
                        statement.executeQuery(
                                "select pg_get_constraintdef(oid) as definition from pg_constraint "
                                        + "where conname = 'till_stock_possible'")) {
            assertTrue(rows.next(), "till_stock_possible is missing, so the database would allow an oversell");
            assertTrue(rows.getString("definition").contains("reserved <= on_hand"), rows.getString("definition"));
        }
    }
}
