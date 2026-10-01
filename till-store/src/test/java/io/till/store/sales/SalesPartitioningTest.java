package io.till.store.sales;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.till.core.Event;
import io.till.core.Line;
import io.till.core.ReservationId;
import io.till.store.StoreTest;
import io.till.store.catalogue.Game;
import io.till.store.catalogue.Games;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * {@code store_sales_daily}'s shape after {@code V7__sales_partitions.sql}, and the two things the
 * rest of the store needs from a partitioned table that it did not need from a plain one: a sale
 * near a month boundary still lands correctly and still counts, and a seven-day read does not pay for
 * months it is not asking about. {@code docs/design/0010-sales-partitions.md} has the reasoning.
 */
class SalesPartitioningTest extends StoreTest {

    private final AtomicLong sequence = new AtomicLong();

    @Autowired
    Games games;

    @Autowired
    SalesPartitionMaintenance maintenance;

    @Nested
    @DisplayName("the schema")
    class Schema {

        @Test
        @DisplayName("is range-partitioned on day, not list or hash partitioned")
        void partitionedByDayRange() {
            String strategy = jdbc.sql(
                            "select partstrat from pg_partitioned_table where partrelid = 'store_sales_daily'::regclass")
                    .query(String.class)
                    .single();
            assertEquals("r", strategy, "'r' is range; 'l' is list and 'h' is hash");

            String partitionKey = jdbc.sql("select pg_get_partkeydef('store_sales_daily'::regclass)")
                    .query(String.class)
                    .single();
            assertEquals("RANGE (day)", partitionKey);
        }

        @Test
        @DisplayName("has a default partition, so a day with no month of its own yet is kept, not refused")
        void hasDefaultPartition() {
            List<String> children = jdbc.sql("select c.relname from pg_inherits i join pg_class c on c.oid = i.inhrelid"
                            + " where i.inhparent = 'store_sales_daily'::regclass order by c.relname")
                    .query(String.class)
                    .list();
            assertTrue(children.contains("store_sales_daily_default"), "children: " + children);
        }

        @Test
        @DisplayName("still refuses a duplicate (sku, day) and a negative unit count")
        void constraintsSurvivedTheConversion() {
            LocalDate day = LocalDate.parse("2026-09-18");
            jdbc.sql("insert into store_sales_daily (sku, day, units) values ('tessera', ?, 1)").param(day).update();

            assertThrows(DataIntegrityViolationException.class, () -> jdbc.sql(
                            "insert into store_sales_daily (sku, day, units) values ('tessera', ?, 1)")
                    .param(day)
                    .update());
            assertThrows(DataIntegrityViolationException.class, () -> jdbc.sql(
                            "insert into store_sales_daily (sku, day, units) values ('canopy', ?, -1)")
                    .param(day)
                    .update());
        }
    }

    @Nested
    @DisplayName("a sale at a month boundary")
    class MonthBoundary {

        @Test
        @DisplayName("the last day of a month and the first of the next land in different partitions, and both count")
        void crossesAMonthBoundary() {
            // T0 is 2026-09-18; the start-up run of the maintenance job already created September's
            // and October's partitions for it. Calling it again here is cheap and makes the test's
            // own assumptions explicit rather than borrowed from another class's side effect.
            maintenance.maintain();

            clock.reset(Instant.parse("2026-09-30T23:00:00Z"));
            apply(sold("tessera", 3, clock.instant()));
            clock.reset(Instant.parse("2026-10-01T01:00:00Z"));
            apply(sold("tessera", 5, clock.instant()));

            String septemberPartition = partitionOf("tessera", LocalDate.parse("2026-09-30"));
            String octoberPartition = partitionOf("tessera", LocalDate.parse("2026-10-01"));
            assertEquals("store_sales_daily_y2026m09", septemberPartition);
            assertEquals("store_sales_daily_y2026m10", octoberPartition);
            assertNotEquals(septemberPartition, octoberPartition);

            Long total = jdbc.sql("select sum(units) from store_sales_daily where sku = 'tessera' and day between ? and ?")
                    .params(LocalDate.parse("2026-09-30"), LocalDate.parse("2026-10-01"))
                    .query(Long.class)
                    .single();
            assertEquals(8L, total, "both rows summed across the partition boundary");

            // And the feature this table exists for: both sales count, read the ordinary way.
            clock.reset(Instant.parse("2026-10-03T12:00:00Z"));
            List<Game> bestSellers = games.bestSellers(5);
            assertEquals("tessera", bestSellers.get(0).sku(), "the only game with any sales in the window");
        }
    }

    @Nested
    @DisplayName("a best-seller read's plan")
    class QueryPlan {

        @Test
        @DisplayName("skips months its seven-day window cannot contain")
        void pruning() {
            // Materialize a handful of months' partitions the way production would: the maintenance
            // job, run periodically, each time a little further along.
            for (String instant : List.of(
                    "2026-06-15T00:00:00Z", "2026-07-15T00:00:00Z", "2026-08-15T00:00:00Z", "2026-09-24T00:00:00Z")) {
                clock.reset(Instant.parse(instant));
                maintenance.maintain();
            }

            // The exact subquery Games.SALES_JOIN runs, with the bind parameter inlined: a literal
            // is what lets Postgres prune at plan time rather than only at execution time.
            List<String> plan = jdbc.sql(
                            "explain select sku, sum(units) as units from store_sales_daily where day >= '2026-09-18' group by sku")
                    .query(String.class)
                    .list();
            String text = String.join("\n", plan);

            assertTrue(text.contains("store_sales_daily_y2026m09"), "the month the window is actually in:\n" + text);
            // June, July and August are entirely before the lower bound, so no row in them could
            // ever match "day >= 2026-09-18" — the planner can rule them out at plan time. An
            // append that still named them would mean every best-seller read keeps scanning history
            // it structurally cannot use.
            assertFalse(text.contains("store_sales_daily_y2026m06"), text);
            assertFalse(text.contains("store_sales_daily_y2026m07"), text);
            assertFalse(text.contains("store_sales_daily_y2026m08"), text);
            // October and default are NOT pruned, and that is correct, not a gap: Games.salesSince()
            // gives this query only a lower bound, so nothing in the plan itself rules out a row
            // dated in October or later. Only the months strictly before the window are provably
            // unreachable, which is exactly what the assertions above establish.
            assertTrue(text.contains("store_sales_daily_y2026m10"), text);
        }
    }

    // --- helpers -----------------------------------------------------------------------------------

    private boolean apply(Event event) {
        long next = sequence.incrementAndGet();
        return projector.apply("test-" + next, next, event);
    }

    private static Event sold(String sku, long units, Instant at) {
        return new Event.StockCommitted(ReservationId.of("r-" + UUID.randomUUID()), List.of(Line.of(sku, units)), at);
    }

    private String partitionOf(String sku, LocalDate day) {
        return jdbc.sql("select tableoid::regclass::text from store_sales_daily where sku = ? and day = ?")
                .params(sku, day)
                .query(String.class)
                .single();
    }
}
