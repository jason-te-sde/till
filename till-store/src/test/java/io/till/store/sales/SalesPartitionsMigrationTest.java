package io.till.store.sales;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * {@code V7__sales_partitions.sql} against a database that already has sales history, not the empty
 * table every other test in this module migrates.
 *
 * <p>{@code store_sales_daily} starts empty in every other test here, the way it does on a fresh
 * deployment — a store has no sales before its first one — so none of those tests can tell a
 * migration that moves every existing row into the right month apart from one that silently drops
 * whatever it could not place. This test gives the migration something to lose, then checks it
 * did not: its own database and its own direct use of Flyway's target version, which the shared
 * Spring context ({@code StoreTest}) does not expose — it always migrates to the latest version in
 * one step.
 */
@Testcontainers
class SalesPartitionsMigrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    @Test
    @DisplayName("converts existing rows without losing any, into the months they actually fall in")
    void preservesExistingRows() throws Exception {
        String url = POSTGRES.getJdbcUrl();
        String user = POSTGRES.getUsername();
        String password = POSTGRES.getPassword();

        // Up to V6 only: store_sales_daily is still the plain, unpartitioned table here.
        Flyway.configure().dataSource(url, user, password).locations("classpath:db/migration").target("6").load().migrate();

        try (Connection connection = DriverManager.getConnection(url, user, password);
                Statement statement = connection.createStatement()) {
            statement.execute("insert into store_sales_daily (sku, day, units) values "
                    + "('widget-a', '2020-01-15', 3), ('widget-a', '2020-02-20', 5), ('widget-b', '2020-01-31', 2)");
        }

        // No target: Flyway resumes from what flyway_schema_history already recorded and applies V7.
        Flyway.configure().dataSource(url, user, password).locations("classpath:db/migration").load().migrate();

        try (Connection connection = DriverManager.getConnection(url, user, password);
                Statement statement = connection.createStatement()) {
            try (ResultSet count = statement.executeQuery("select count(*) from store_sales_daily")) {
                count.next();
                assertEquals(3, count.getInt(1), "every row from before the conversion is still there");
            }
            try (ResultSet units = statement.executeQuery(
                    "select units from store_sales_daily where sku = 'widget-a' and day = '2020-01-15'")) {
                units.next();
                assertEquals(3, units.getInt(1), "not just present, but carrying the value it had before");
            }
            try (ResultSet rows = statement.executeQuery(
                    "select sku, day, tableoid::regclass::text as partition from store_sales_daily order by sku, day")) {
                List<String> located = new ArrayList<>();
                while (rows.next()) {
                    located.add(rows.getString("sku") + " " + rows.getString("day") + " " + rows.getString("partition"));
                }
                assertEquals(
                        List.of(
                                "widget-a 2020-01-15 store_sales_daily_y2020m01",
                                "widget-a 2020-02-20 store_sales_daily_y2020m02",
                                "widget-b 2020-01-31 store_sales_daily_y2020m01"),
                        located,
                        "each row in the partition for its own month, not left behind in default");
            }
        }
    }
}
