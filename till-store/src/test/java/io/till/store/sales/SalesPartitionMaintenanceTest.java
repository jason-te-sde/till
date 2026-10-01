package io.till.store.sales;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.till.store.StoreProperties;
import io.till.store.StoreTest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@link SalesPartitionMaintenance}: keeps {@code store_sales_daily}'s monthly partitions one month
 * ahead of the clock, and drops whatever retention no longer wants. {@code docs/design/0010-sales-partitions.md}
 * has the reasoning; this class is the behaviour that reasoning promises.
 */
class SalesPartitionMaintenanceTest extends StoreTest {

    @Autowired
    SalesPartitionMaintenance maintenance;

    @Autowired
    StoreProperties properties;

    @Autowired
    TransactionTemplate transactionTemplate;

    @Autowired
    DataSource dataSource;

    @Test
    @DisplayName("ensures this month's and next month's partitions exist")
    void createsCurrentAndNextMonth() {
        clock.reset(Instant.parse("2030-03-10T00:00:00Z"));
        assertFalse(partitionExists("store_sales_daily_y2030m03"));
        assertFalse(partitionExists("store_sales_daily_y2030m04"));

        maintenance.maintain();

        assertTrue(partitionExists("store_sales_daily_y2030m03"), "this month");
        assertTrue(partitionExists("store_sales_daily_y2030m04"), "next month");
    }

    @Test
    @DisplayName("running it again is a no-op, not an error")
    void idempotent() {
        clock.reset(Instant.parse("2030-04-10T00:00:00Z"));

        assertDoesNotThrow(() -> {
            maintenance.maintain();
            maintenance.maintain();
            maintenance.maintain();
        });
        assertTrue(partitionExists("store_sales_daily_y2030m04"));
        assertTrue(partitionExists("store_sales_daily_y2030m05"));
    }

    @Test
    @DisplayName("rows that landed in the default partition before their month existed are moved, not lost")
    void rescuesRowsFromDefault() {
        clock.reset(Instant.parse("2030-06-10T00:00:00Z"));
        // Nobody has created June 2030 yet, so this insert has nowhere to go but default.
        jdbc.sql("insert into store_sales_daily (sku, day, units) values ('tessera', '2030-06-15', 7)").update();
        assertEquals("store_sales_daily_default", partitionOf("tessera", LocalDate.parse("2030-06-15")));

        maintenance.maintain();

        assertEquals("store_sales_daily_y2030m06", partitionOf("tessera", LocalDate.parse("2030-06-15")));
        Long units = jdbc.sql("select units from store_sales_daily where sku = 'tessera' and day = '2030-06-15'")
                .query(Long.class)
                .single();
        assertEquals(7L, units, "the row itself, not just its home, survived the move");
    }

    @Test
    @DisplayName("drops a month only once every day in it is older than the retention window")
    void dropsOnlyWhatRetentionNoLongerWants() {
        SalesPartitionMaintenance shortRetention = new SalesPartitionMaintenance(
                jdbc, clock, transactionTemplate, withSales(new StoreProperties.Sales(Period.ofMonths(2), Duration.ofSeconds(2))));

        clock.reset(Instant.parse("2030-09-10T00:00:00Z"));
        shortRetention.maintain(); // creates 2030-09, 2030-10

        clock.reset(Instant.parse("2030-12-10T00:00:00Z"));
        shortRetention.maintain(); // creates 2030-12, 2031-01; two months back from here is 2030-10-10

        assertFalse(partitionExists("store_sales_daily_y2030m09"), "September ended well before the cutoff");
        assertTrue(partitionExists("store_sales_daily_y2030m10"), "October still has days inside the window");
    }

    @Test
    @Timeout(10)
    @DisplayName("another instance already holding the maintenance claim makes this pass a no-op, not an error")
    void skipsWhenAnotherInstanceHoldsTheClaim() throws Exception {
        clock.reset(Instant.parse("2032-03-10T00:00:00Z"));

        try (Connection other = dataSource.getConnection()) {
            try (PreparedStatement lock = other.prepareStatement("select pg_advisory_lock(?)")) {
                lock.setLong(1, SalesPartitionMaintenance.MAINTENANCE_LOCK);
                lock.execute();
            }

            assertDoesNotThrow(maintenance::maintain);

            assertFalse(partitionExists("store_sales_daily_y2032m03"), "the other session is holding the claim");
            assertFalse(partitionExists("store_sales_daily_y2032m04"));

            try (PreparedStatement unlock = other.prepareStatement("select pg_advisory_unlock(?)")) {
                unlock.setLong(1, SalesPartitionMaintenance.MAINTENANCE_LOCK);
                unlock.execute();
            }
        }

        // The claim is free again now, so an ordinary pass behaves exactly as every other test here.
        maintenance.maintain();
        assertTrue(partitionExists("store_sales_daily_y2032m03"));
    }

    @Test
    @Timeout(10)
    @DisplayName("a lock the DDL cannot get within lock_timeout rolls back the whole pass, not just the last step")
    void lockTimeoutRollsBackTheWholePass() throws Exception {
        SalesPartitionMaintenance shortTimeout = new SalesPartitionMaintenance(
                jdbc, clock, transactionTemplate,
                withSales(new StoreProperties.Sales(properties.sales().retention(), Duration.ofMillis(200))));

        clock.reset(Instant.parse("2033-07-10T00:00:00Z"));
        // Nobody has created July 2033 yet, so this lands in default, exactly the row the rescue path
        // (create a standalone table, move the rows out of default, attach it) has to carry across.
        jdbc.sql("insert into store_sales_daily (sku, day, units) values ('tessera', '2033-07-15', 9)").update();
        assertEquals("store_sales_daily_default", partitionOf("tessera", LocalDate.parse("2033-07-15")));

        try (Connection other = dataSource.getConnection()) {
            other.setAutoCommit(false);
            try (Statement statement = other.createStatement()) {
                // The strongest lock there is on the parent — and PostgreSQL extends it to every
                // existing partition, default included, so every statement the pass would run
                // against either one has to wait for it.
                statement.execute("lock table store_sales_daily in access exclusive mode");
            }

            Instant startedWaiting = Instant.now();
            assertThrows(RuntimeException.class, shortTimeout::maintain);
            Duration waited = Duration.between(startedWaiting, Instant.now());
            // Well under the production default (two seconds): proof the configured 200ms was the
            // value actually used, not an incidental side effect of the test being fast otherwise.
            assertTrue(waited.compareTo(Duration.ofSeconds(1)) < 0, "waited " + waited);

            // pg_class is a system catalog, not store_sales_daily itself, so this read is not blocked
            // by the access-exclusive lock other is still holding: the assertion below would be.
            assertFalse(partitionExists("store_sales_daily_y2033m07"),
                    "the whole pass rolled back: no half-created, unattached table left behind");

            // Release the lock before reading store_sales_daily directly — an ordinary read against
            // it would itself block on the very lock this test is holding, with no lock_timeout of
            // its own to time it out.
            other.rollback();
        }

        assertEquals("store_sales_daily_default", partitionOf("tessera", LocalDate.parse("2033-07-15")),
                "the rows never left default");

        // The lock is gone; the next pass (an ordinary one, the production timeout is fine) succeeds.
        maintenance.maintain();

        assertTrue(isAttachedPartition("store_sales_daily_y2033m07"));
        assertEquals("store_sales_daily_y2033m07", partitionOf("tessera", LocalDate.parse("2033-07-15")));
        Long units = jdbc.sql("select units from store_sales_daily where sku = 'tessera' and day = '2033-07-15'")
                .query(Long.class)
                .single();
        assertEquals(9L, units);
    }

    private boolean partitionExists(String name) {
        Boolean found =
                jdbc.sql("select exists(select 1 from pg_class where relname = ?)").param(name).query(Boolean.class).single();
        return Boolean.TRUE.equals(found);
    }

    private boolean isAttachedPartition(String name) {
        Boolean found = jdbc.sql("select exists(select 1 from pg_inherits i join pg_class c on c.oid = i.inhrelid"
                        + " where i.inhparent = 'store_sales_daily'::regclass and c.relname = ?)")
                .param(name)
                .query(Boolean.class)
                .single();
        return Boolean.TRUE.equals(found);
    }

    private String partitionOf(String sku, LocalDate day) {
        return jdbc.sql("select tableoid::regclass::text from store_sales_daily where sku = ? and day = ?")
                .params(sku, day)
                .query(String.class)
                .single();
    }

    private StoreProperties withSales(StoreProperties.Sales sales) {
        return new StoreProperties(properties.till(), properties.kafka(), properties.auth(), properties.checkout(),
                properties.demo(), properties.catalogue(), sales);
    }
}
