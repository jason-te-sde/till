package io.till.store.sales;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.till.store.StoreProperties;
import io.till.store.StoreTest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

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
        SalesPartitionMaintenance shortRetention =
                new SalesPartitionMaintenance(jdbc, clock, withRetention(Period.ofMonths(2)));

        clock.reset(Instant.parse("2030-09-10T00:00:00Z"));
        shortRetention.maintain(); // creates 2030-09, 2030-10

        clock.reset(Instant.parse("2030-12-10T00:00:00Z"));
        shortRetention.maintain(); // creates 2030-12, 2031-01; two months back from here is 2030-10-10

        assertFalse(partitionExists("store_sales_daily_y2030m09"), "September ended well before the cutoff");
        assertTrue(partitionExists("store_sales_daily_y2030m10"), "October still has days inside the window");
    }

    private boolean partitionExists(String name) {
        Boolean found =
                jdbc.sql("select exists(select 1 from pg_class where relname = ?)").param(name).query(Boolean.class).single();
        return Boolean.TRUE.equals(found);
    }

    private String partitionOf(String sku, LocalDate day) {
        return jdbc.sql("select tableoid::regclass::text from store_sales_daily where sku = ? and day = ?")
                .params(sku, day)
                .query(String.class)
                .single();
    }

    private StoreProperties withRetention(Period retention) {
        return new StoreProperties(properties.till(), properties.kafka(), properties.auth(), properties.checkout(),
                properties.demo(), properties.catalogue(), new StoreProperties.Sales(retention));
    }
}
