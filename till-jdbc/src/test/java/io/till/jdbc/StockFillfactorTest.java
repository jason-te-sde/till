package io.till.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceLock;

/**
 * {@code till_stock}'s storage parameters (docs/design/0009-hot-sku-shards.md's "Later" note).
 *
 * <p>A SKU split sixteen ways still has all its shards on the same handful of 8&nbsp;KB pages at the
 * default fillfactor: 512 rows (32 games &times; 16 shards) pack onto five. A page is what a buffer pin
 * guards and what a HOT update needs free space on, so that is a unit of contention the shards in
 * ADR 9 do not divide. {@code V4__stock_fillfactor.sql} gives the table a low fillfactor and rebuilds
 * it, so the rows spread over many more pages whether they were written after the migration or were
 * already on disk when it ran. Setting the parameter alone would do only the first, and the second is a
 * path no deployment here takes (each migrates an empty table and stocks it afterwards), which is why
 * it has a test of its own.
 */
// Suites that share the one database run one at a time; see the note in JdbcLedgerTest.
@ResourceLock("till-database")
@ExtendWith(RequiresDatabase.class)
class StockFillfactorTest {

    private static final String V4 = "db/migration/V4__stock_fillfactor.sql";

    /**
     * How many distinct pages hold a live row. {@code ctid} is {@code (block,offset)}, and splitting its
     * text form is the direct way to ask without the pageinspect extension installed.
     */
    private static final String PAGES_WITH_ROWS =
            "select count(distinct split_part(trim(both '()' from ctid::text), ',', 1)) from till_stock";

    private DataSource dataSource;

    @BeforeEach
    void setUp() {
        dataSource = PostgresFixture.dataSource();
        PostgresFixture.reset();
    }

    @Test
    @DisplayName("till_stock carries a low fillfactor, so a page is never packed too tight for a HOT update")
    void fillfactorIsSet() throws SQLException {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet rows =
                        statement.executeQuery("select reloptions from pg_class where relname = 'till_stock'")) {
            assertTrue(rows.next(), "till_stock is missing from pg_class");
            String reloptions = rows.getString("reloptions");
            assertTrue(
                    reloptions != null && reloptions.contains("fillfactor=10"),
                    "expected till_stock's reloptions to set fillfactor=10, got " + reloptions);
        }
    }

    @Test
    @DisplayName("512 rows stocked after the migration land on many more pages than fillfactor 100 packs them onto")
    void rowsStockedAfterTheMigrationAreSpread() throws SQLException {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            insertLoadTestStock(connection);

            // Measured at fillfactor 100: these 512 rows fit on 5 pages, and about 50 at fillfactor 10.
            // 20 is a floor well clear of both, that fails if the fillfactor is not applied to inserts.
            long pages = count(statement, PAGES_WITH_ROWS);
            assertTrue(pages > 20, "512 rows stocked after the migration landed on only " + pages + " page(s)");
        }
    }

    @Test
    @DisplayName("512 rows that predate the migration are rewritten onto many pages, with their guard and indexes intact")
    void rowsThatPredateTheMigrationAreRewritten() throws SQLException {
        int v4 = JdbcSchema.RESOURCES.indexOf(V4);
        assertTrue(v4 > 0, V4 + " is not in JdbcSchema.RESOURCES: " + JdbcSchema.RESOURCES);
        List<String> everythingBeforeV4 = new ArrayList<>();
        for (String resource : JdbcSchema.RESOURCES.subList(0, v4)) {
            everythingBeforeV4.addAll(JdbcSchema.statements(resource));
        }

        // A schema of its own on a connection of its own: the migrations are unqualified, so a search
        // path to the scratch schema makes them build there, and an unpooled connection takes that path
        // with it when it closes instead of handing it to the next test. The shared tables are untouched.
        try (Connection connection =
                        DriverManager.getConnection(
                                TestDatabase.url(), TestDatabase.username(), TestDatabase.password());
                Statement statement = connection.createStatement()) {
            statement.execute("drop schema if exists fillfactor_probe cascade");
            statement.execute("create schema fillfactor_probe");
            try {
                statement.execute("set search_path to fillfactor_probe");
                for (String sql : everythingBeforeV4) {
                    statement.execute(sql);
                }
                insertLoadTestStock(connection);
                long packed = count(statement, PAGES_WITH_ROWS);
                assertTrue(
                        packed <= 8,
                        "the rows should start out packed onto a handful of pages, or this proves nothing; they are on "
                                + packed);

                for (String sql : JdbcSchema.statements(V4)) {
                    statement.execute(sql);
                }

                assertEquals(512, count(statement, "select count(*) from till_stock"), "the rewrite lost or duplicated rows");
                assertEquals(
                        51_200,
                        count(statement, "select sum(on_hand) from till_stock"),
                        "the rewrite changed what the rows hold");
                long spread = count(statement, PAGES_WITH_ROWS);
                assertTrue(
                        spread > 20,
                        "512 rows already on " + packed + " page(s) when the migration ran are on " + spread
                                + " after it; expected the rewrite to spread them");
                assertEquals(
                        "{fillfactor=10}",
                        text(statement, "select reloptions::text from pg_class where oid = 'till_stock'::regclass"));
                assertEquals(
                        "till_stock_pkey,till_stock_possible,till_stock_shard",
                        text(
                                statement,
                                "select string_agg(conname, ',' order by conname) from pg_constraint "
                                        + "where conrelid = 'till_stock'::regclass and contype in ('c', 'p')"),
                        "the rewrite did not give the table its constraints back");
                assertEquals(
                        "till_stock_pkey,till_stock_sku_c",
                        text(
                                statement,
                                "select string_agg(indexname, ',' order by indexname) from pg_indexes "
                                        + "where schemaname = 'fillfactor_probe' and tablename = 'till_stock'"),
                        "the rewrite did not give the table its indexes back");

                // The oversell guard is the reason the table has constraints at all; a rewrite that
                // dropped it would leave every other assertion here green.
                SQLException refused =
                        assertThrows(
                                SQLException.class,
                                () -> statement.execute(
                                        "update till_stock set reserved = on_hand + 1 where sku = 'game-0' and shard = 0"));
                assertEquals("23514", refused.getSQLState(), "the oversell guard was not a check violation: " + refused);

                // A check constraint passes a null, so that guard also rests on the not null
                // constraints, which "create table as" does not carry over any more than the defaults.
                SQLException nulled =
                        assertThrows(
                                SQLException.class,
                                () -> statement.execute(
                                        "update till_stock set on_hand = null where sku = 'game-0' and shard = 0"));
                assertEquals("23502", nulled.getSQLState(), "a null on_hand was not refused as a not null violation: " + nulled);
                statement.execute("insert into till_stock (sku, on_hand, reserved, version) values ('defaults', 1, 0, 0)");
                assertEquals(
                        "0,true",
                        text(
                                statement,
                                "select format('%s,%s', shard, (updated_at is not null)::text) from till_stock "
                                        + "where sku = 'defaults'"),
                        "the rewrite did not give shard and updated_at their defaults back");
            } finally {
                statement.execute("drop schema if exists fillfactor_probe cascade");
            }
        }
    }

    /** 32 games split 16 ways, one statement a row in SKU and then shard order: the load test's stock. */
    private static void insertLoadTestStock(Connection connection) throws SQLException {
        try (PreparedStatement insert =
                connection.prepareStatement(
                        "insert into till_stock (sku, shard, on_hand, reserved, version) values (?, ?, 100, 0, 0)")) {
            for (int sku = 0; sku < 32; sku++) {
                for (int shard = 0; shard < 16; shard++) {
                    insert.setString(1, "game-" + sku);
                    insert.setInt(2, shard);
                    insert.addBatch();
                }
            }
            insert.executeBatch();
        }
    }

    private static long count(Statement statement, String sql) throws SQLException {
        try (ResultSet rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getLong(1);
        }
    }

    private static String text(Statement statement, String sql) throws SQLException {
        try (ResultSet rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getString(1);
        }
    }
}
