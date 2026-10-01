package io.till.store.sales;

import io.till.store.StoreProperties;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Keeps {@code store_sales_daily}'s monthly partitions one month ahead of the clock, and drops the
 * ones retention no longer wants. {@code docs/design/0010-sales-partitions.md} has the reasoning;
 * {@code V7__sales_partitions.sql} is what this keeps up after the migration's one-time setup ends.
 *
 * <p>Shaped like {@code till-server}'s {@code RetentionSweeper}, {@code ExpirySweeper} and
 * {@code OutboxPublisher} — the prior art in this codebase for "a thing that must keep happening
 * without a release" — adapted here because {@code till-store} had none of its own yet.
 *
 * <h2>Why a partition's month comes from the clock, not from the database</h2>
 *
 * <p>Every date this class computes comes from the injected {@link Clock}, never from
 * {@code current_date}. In production that clock is the system clock, so "this month" tracks real
 * time exactly as an operator would expect. In a test it is a clock a test controls, so a test can
 * put "today" wherever a scenario needs it without waiting for the calendar — the same reason
 * {@link io.till.store.catalogue.Games} and {@link io.till.store.events.Projector} take a
 * {@code Clock} rather than reading the system clock directly.
 *
 * <h2>The default partition, and why a month is not always a plain create</h2>
 *
 * <p>PostgreSQL refuses to create or attach a partition that would pull matching rows out from
 * under the {@code default} partition without being told to move them first. Ordinarily that never
 * happens — a month's partition is created before anything is ever inserted for it — but a run
 * skipped for longer than a month, or a clock that jumped, can leave rows for an unpartitioned month
 * sitting in {@code default}. {@link #ensureMonth} checks for that case and, when it finds rows
 * waiting, moves them into a table built with the same primary key and index the ordinary path would
 * have given it, then attaches that — rather than letting the create fail or leaving the rows where a
 * seven-day read would have to scan over all of {@code default} to find them.
 */
@Component
class SalesPartitionMaintenance {

    private static final Logger LOG = LoggerFactory.getLogger(SalesPartitionMaintenance.class);

    /** The naming scheme this class both writes and reads back; see {@link #partitionName}. */
    private static final Pattern MONTHLY_PARTITION = Pattern.compile("store_sales_daily_y(\\d{4})m(\\d{2})");

    private final JdbcClient jdbc;
    private final Clock clock;
    private final StoreProperties.Sales properties;

    SalesPartitionMaintenance(JdbcClient jdbc, Clock clock, StoreProperties properties) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.properties = properties.sales();
    }

    /**
     * The one run at start-up. A plain listener, not a thread of its own: unlike
     * {@code DemoStock}'s call to the ledger over HTTP, this is local DDL against a database Flyway
     * has already migrated by the time the application is ready, so there is nothing slow or
     * unreliable here to hide from the application's own start-up.
     */
    @EventListener(ApplicationReadyEvent.class)
    void onStartup() {
        runProtected();
    }

    /**
     * The daily run. An initial delay of one interval, not zero: {@link #onStartup} already covers
     * the moment the application becomes ready, and firing again immediately would only be a second,
     * redundant pass over the same two months.
     */
    @Scheduled(fixedDelayString = "24h", initialDelayString = "24h")
    void onSchedule() {
        runProtected();
    }

    /**
     * Guards both triggers the same way every scheduled job in this codebase does: an uncaught
     * exception from a {@code @Scheduled} method cancels every future run of it for the life of the
     * process, which would silently stop partitions from ever being created again.
     */
    private void runProtected() {
        try {
            maintain();
        } catch (RuntimeException e) {
            LOG.error("sales partition maintenance failed; it will try again next interval", e);
        }
    }

    /** One pass: this month, next month, and whatever the retention window no longer wants. */
    void maintain() {
        LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        YearMonth thisMonth = YearMonth.from(today);
        ensureMonth(thisMonth);
        ensureMonth(thisMonth.plusMonths(1));
        dropExpired(today);
    }

    private void ensureMonth(YearMonth month) {
        String name = partitionName(month);
        if (exists(name)) {
            return;
        }
        LocalDate start = month.atDay(1);
        LocalDate end = month.plusMonths(1).atDay(1);
        if (hasDefaultRows(start, end)) {
            rescueFromDefault(name, start, end);
            LOG.info("created partition {} for {}, moving rows out of the default partition first", name, month);
        } else {
            jdbc.sql("create table if not exists " + name + " partition of store_sales_daily for values from ('"
                            + start + "') to ('" + end + "')")
                    .update();
            LOG.info("created partition {} for {}", name, month);
        }
    }

    private void rescueFromDefault(String name, LocalDate start, LocalDate end) {
        // A standalone table first, not yet a partition: PostgreSQL will not create a partition
        // directly over rows default is already holding for that range.
        jdbc.sql("create table " + name + " (like store_sales_daily_default including defaults including constraints)")
                .update();
        // The ordinary "create table ... partition of ..." path gets these for free, propagated
        // from the parent; attaching a pre-existing table does not, so they are added by hand.
        jdbc.sql("alter table " + name + " add primary key (sku, day)").update();
        jdbc.sql("create index " + name + "_by_day on " + name + " (day, sku)").update();
        jdbc.sql("with moved as ("
                        + "delete from store_sales_daily_default where day >= ? and day < ? returning sku, day, units"
                        + ") insert into " + name + " (sku, day, units) select sku, day, units from moved")
                .params(start, end)
                .update();
        jdbc.sql("alter table store_sales_daily attach partition " + name + " for values from ('" + start + "') to ('"
                        + end + "')")
                .update();
    }

    /** Drops every monthly partition entirely older than retention; never touches {@code default}. */
    private void dropExpired(LocalDate today) {
        LocalDate cutoff = today.minus(properties.retention());
        List<String> partitions = jdbc.sql("select c.relname from pg_inherits i join pg_class c on c.oid = i.inhrelid"
                        + " where i.inhparent = 'store_sales_daily'::regclass order by c.relname")
                .query(String.class)
                .list();
        for (String name : partitions) {
            YearMonth month = monthOf(name);
            // Not one of ours by this naming scheme — the default partition, or anything created by
            // hand outside it — is never a candidate for dropping.
            if (month == null) {
                continue;
            }
            LocalDate end = month.plusMonths(1).atDay(1);
            if (!end.isAfter(cutoff)) {
                jdbc.sql("drop table if exists " + name).update();
                LOG.info("dropped partition {} for {}: entirely older than the {} retention window", name, month,
                        properties.retention());
            }
        }
    }

    private boolean exists(String relname) {
        Boolean found =
                jdbc.sql("select exists(select 1 from pg_class where relname = ?)").param(relname).query(Boolean.class).single();
        return Boolean.TRUE.equals(found);
    }

    private boolean hasDefaultRows(LocalDate start, LocalDate end) {
        Boolean found = jdbc.sql("select exists(select 1 from store_sales_daily_default where day >= ? and day < ?)")
                .params(start, end)
                .query(Boolean.class)
                .single();
        return Boolean.TRUE.equals(found);
    }

    private static String partitionName(YearMonth month) {
        return String.format("store_sales_daily_y%04dm%02d", month.getYear(), month.getMonthValue());
    }

    private static YearMonth monthOf(String relname) {
        Matcher matcher = MONTHLY_PARTITION.matcher(relname);
        if (!matcher.matches()) {
            return null;
        }
        return YearMonth.of(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)));
    }
}
