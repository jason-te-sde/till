package io.till.store.sales;

import io.till.store.StoreProperties;
import java.sql.SQLException;
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
import org.springframework.transaction.support.TransactionTemplate;

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
 *
 * <h2>One transaction, one claim</h2>
 *
 * <p>The whole pass — the rescue sequence above included — runs inside one transaction, entered only
 * after {@link #CLAIM_MAINTENANCE} succeeds, exactly as {@code JdbcLedger}'s outbox publisher claims
 * before it drains ({@code till-jdbc}, {@code CLAIM_PUBLISHING}/{@code PUBLISHING_LOCK}). Two things
 * that pattern buys here:
 *
 * <ul>
 *   <li><b>Every store instance runs {@link #maintain} at the same moment on a fresh deploy</b>
 *       ({@code ApplicationReadyEvent}), and {@code create table if not exists} is not race-free in
 *       PostgreSQL: two sessions can both pass the existence check and then collide creating the same
 *       relation. The claim means only one instance ever runs the DDL below at a time; the rest see
 *       {@link #maintain} return having done nothing, which is indistinguishable from a pass that
 *       simply found nothing to do.
 *   <li><b>A task that dies mid-rescue</b> — after moving rows out of {@code default} but before
 *       attaching the table they were moved into — used to leave that table behind, unattached, under
 *       the name the next pass's existence check matches: the month's sales would be invisible to
 *       every query, permanently, and silently. One transaction makes that impossible. The connection
 *       dropping with the process rolls everything in the pass back, default included, so the next
 *       pass starts from exactly where the last successful one left off.
 * </ul>
 *
 * <p>{@code set local lock_timeout}, set once the claim is held, bounds how long the DDL below will
 * wait for the lock it needs on {@code store_sales_daily} — which PostgreSQL extends to every one of
 * its partitions, {@code default} included. Without it, a slow query already holding that lock would
 * queue every later reader and writer of the table behind this pass, for as long as the slow query
 * runs: PostgreSQL's lock queue is first in, first out, so a DDL statement waiting for a lock holds
 * its place in line even against requests that would not otherwise conflict with each other. A
 * partition is created a month ahead of when anything needs it, so losing a pass to the timeout and
 * retrying at the next interval costs nothing a caller would notice.
 */
@Component
class SalesPartitionMaintenance {

    private static final Logger LOG = LoggerFactory.getLogger(SalesPartitionMaintenance.class);

    /** The naming scheme this class both writes and reads back; see {@link #partitionName}. */
    private static final Pattern MONTHLY_PARTITION = Pattern.compile("store_sales_daily_y(\\d{4})m(\\d{2})");

    /**
     * The maintenance claim: a transaction-scoped advisory lock, so it is released automatically when
     * the transaction ends — committed, rolled back, or dropped with the connection of an instance
     * that died holding it — never left held by a session that is no longer there to release it.
     */
    private static final String CLAIM_MAINTENANCE = "select pg_try_advisory_xact_lock(?)";

    /** "tillpart" in ASCII. Any fixed number would do; this one says whose it is in {@code pg_locks}. */
    static final long MAINTENANCE_LOCK = 0x74696c6c70617274L;

    /** PostgreSQL's SQLSTATE for a statement that gave up waiting for a lock. */
    private static final String LOCK_NOT_AVAILABLE = "55P03";

    private final JdbcClient jdbc;
    private final Clock clock;
    private final TransactionTemplate transaction;
    private final StoreProperties.Sales properties;

    SalesPartitionMaintenance(JdbcClient jdbc, Clock clock, TransactionTemplate transaction, StoreProperties properties) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.transaction = transaction;
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
     * process, which would silently stop partitions from ever being created again. A lock-timeout
     * failure is logged at a lower level than any other, because it is the expected outcome of
     * something else legitimately holding {@code store_sales_daily} open for a while, not a bug.
     */
    private void runProtected() {
        try {
            maintain();
        } catch (RuntimeException e) {
            if (isLockTimeout(e)) {
                LOG.warn("sales partition maintenance gave up waiting {} for a lock on store_sales_daily; it will "
                                + "try again next interval. If this keeps happening, find what is holding the table "
                                + "open that long — this job is not where the problem is.",
                        properties.lockTimeout(), e);
            } else {
                LOG.error("sales partition maintenance failed; it will try again next interval", e);
            }
        }
    }

    /**
     * One pass, in one transaction, entered only once this instance holds {@link #MAINTENANCE_LOCK}:
     * this month, next month, and whatever the retention window no longer wants. See the class
     * documentation for why both the claim and the single transaction matter.
     */
    void maintain() {
        transaction.executeWithoutResult(status -> {
            Boolean claimed = jdbc.sql(CLAIM_MAINTENANCE).param(MAINTENANCE_LOCK).query(Boolean.class).single();
            if (!Boolean.TRUE.equals(claimed)) {
                LOG.debug("another instance already holds the sales partition maintenance claim; skipping this pass");
                return;
            }
            jdbc.sql("set local lock_timeout = '" + properties.lockTimeout().toMillis() + "ms'").update();

            LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
            YearMonth thisMonth = YearMonth.from(today);
            ensureMonth(thisMonth);
            ensureMonth(thisMonth.plusMonths(1));
            dropExpired(today);
        });
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

    /** Walks the cause chain for the SQLSTATE PostgreSQL uses for an expired {@code lock_timeout}. */
    private static boolean isLockTimeout(Throwable e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sqlException && LOCK_NOT_AVAILABLE.equals(sqlException.getSQLState())) {
                return true;
            }
        }
        return false;
    }
}
