package io.till.jdbc;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.till.core.Command;
import io.till.core.ConflictException;
import io.till.core.Decision;
import io.till.core.IdempotencyKey;
import io.till.core.Ledger;
import io.till.core.Line;
import io.till.core.Outcome;
import io.till.core.ReservationId;
import io.till.core.Sku;
import io.till.core.Snapshot;
import io.till.core.Till;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceLock;

/**
 * How much of the ledger's work under contention is thrown away: checkouts from many callers at once
 * on a few SKUs, against PostgreSQL, counting what the database was asked to do for each one that
 * completed.
 *
 * <p>Not part of the build. It runs for as long as it is told to and asserts nothing; it is how a
 * change to the write path is measured before it costs a load test. Against a server limited to two
 * CPUs, as the load test's database is:
 *
 * <pre>
 * docker run -d --rm --name till-bench --cpus 2 --memory 1g -p 55432:5432 \
 *     -e POSTGRES_USER=till -e POSTGRES_PASSWORD=till postgres:17-alpine \
 *     -c shared_preload_libraries=pg_stat_statements -c max_connections=200
 * TILL_TEST_DB_URL=jdbc:postgresql://localhost:55432/till mvn -q -pl till-jdbc -am test \
 *     -Dtest=ContentionBenchmark -Dsurefire.failIfNoSpecifiedTests=false -Dtill.benchmark=true
 * </pre>
 *
 * <p>Each caller checks out one unit of a SKU chosen at random — a reservation, then its commit or,
 * one time in ten, its release — as the load test's shoppers do, with stock enough that nobody is
 * refused. {@code -Dbench.callers}, {@code bench.pool}, {@code bench.skus}, {@code bench.seconds} and
 * {@code bench.warmup} change the shape, and {@code bench.shards} splits every SKU that many ways
 * before the run (ADR 9).
 */
@EnabledIfSystemProperty(named = "till.benchmark", matches = "true")
@ResourceLock("till-database")
@ExtendWith(RequiresDatabase.class)
class ContentionBenchmark {

    private static final Duration TTL = Duration.ofMinutes(15);

    @Test
    void checkouts() throws Exception {
        int callers = Integer.getInteger("bench.callers", 64);
        int poolSize = Integer.getInteger("bench.pool", 32);
        int skuCount = Integer.getInteger("bench.skus", 32);
        int seconds = Integer.getInteger("bench.seconds", 30);
        int warmup = Integer.getInteger("bench.warmup", 5);
        int shards = Integer.getInteger("bench.shards", 1);

        PostgresFixture.dataSource();
        PostgresFixture.reset();
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(TestDatabase.url());
        config.setUsername(TestDatabase.username());
        config.setPassword(TestDatabase.password());
        config.setMaximumPoolSize(poolSize);
        config.setPoolName("till-bench");
        try (HikariDataSource pool = new HikariDataSource(config)) {
            Counting ledger = new Counting(new JdbcLedger(pool));
            Till till = Till.builder(ledger).build();
            List<Sku> skus = new ArrayList<>();
            for (int i = 0; i < skuCount; i++) {
                Sku sku = Sku.of("game-" + i);
                skus.add(sku);
                till.adjust(IdempotencyKey.of("stock-" + i), sku, 100_000_000);
                if (shards > 1) {
                    till.shard(IdempotencyKey.of("split-" + i), sku, shards);
                }
            }

            AtomicLong serial = new AtomicLong();
            LongAdder checkouts = new LongAdder();
            LongAdder exhausted = new LongAdder();
            long started = System.nanoTime();
            long measureFrom = started + Duration.ofSeconds(warmup).toNanos();
            long end = measureFrom + Duration.ofSeconds(seconds).toNanos();
            Counters before = null;
            List<Future<long[]>> latencies = new ArrayList<>();
            try (ExecutorService crowd = Executors.newVirtualThreadPerTaskExecutor()) {
                for (int c = 0; c < callers; c++) {
                    latencies.add(crowd.submit(() -> {
                        long[] times = new long[1024];
                        int count = 0;
                        ThreadLocalRandom random = ThreadLocalRandom.current();
                        while (System.nanoTime() < end) {
                            long n = serial.incrementAndGet();
                            Sku sku = skus.get(random.nextInt(skus.size()));
                            long t0 = System.nanoTime();
                            boolean measured = t0 >= measureFrom;
                            try {
                                ReservationId id = ReservationId.of("r-" + n);
                                Outcome held = till.reserve(IdempotencyKey.of("c-" + n), id, List.of(new Line(sku, 1)), TTL);
                                if (held.ok()) {
                                    if (random.nextInt(10) == 0) {
                                        till.release(IdempotencyKey.of("x-" + n), id);
                                    } else {
                                        till.commit(IdempotencyKey.of("p-" + n), id);
                                    }
                                }
                                if (measured) {
                                    checkouts.increment();
                                    if (count == times.length) {
                                        times = Arrays.copyOf(times, count * 2);
                                    }
                                    times[count++] = System.nanoTime() - t0;
                                }
                            } catch (ConflictException e) {
                                if (measured) {
                                    exhausted.increment();
                                }
                            }
                        }
                        return Arrays.copyOf(times, count);
                    }));
                }
                Thread.sleep(Math.max(0, Duration.ofNanos(measureFrom - System.nanoTime()).toMillis()));
                resetStatements(pool);
                before = Counters.read(pool);
                ledger.measuring = true;
            }
            ledger.measuring = false;
            // A backend reports its transactions when it goes idle, and waits up to ten seconds to.
            Thread.sleep(11_000);
            Counters after = Counters.read(pool);

            long[] all = latencies.stream().flatMapToLong(f -> Arrays.stream(join(f))).sorted().toArray();
            double perSecond = checkouts.sum() / (double) seconds;
            double commands = ledger.decided.sum();
            System.out.printf(Locale.ROOT, "%nContention: %d callers, %d connections, %d SKUs in %d shards each, %d s measured after %d s%n",
                    callers, poolSize, skuCount, shards, seconds, warmup);
            System.out.printf(Locale.ROOT, "  checkouts           %,d (%.1f a second)%n", checkouts.sum(), perSecond);
            System.out.printf(Locale.ROOT, "  checkout latency    p50 %.1f ms, p99 %.1f ms%n", percentile(all, 0.50), percentile(all, 0.99));
            System.out.printf(Locale.ROOT, "  gave up (conflicts) %,d%n", exhausted.sum());
            System.out.printf(Locale.ROOT, "  decisions applied   %,.0f, of which conflicted %,d (%.1f%%)%n",
                    commands, ledger.conflicts.sum(), 100.0 * ledger.conflicts.sum() / Math.max(1, commands));
            System.out.printf(Locale.ROOT, "  snapshots loaded    %,d%n", ledger.loads.sum());
            if (before != null && after != null) {
                long commits = after.commits - before.commits;
                long rollbacks = after.rollbacks - before.rollbacks;
                System.out.printf(Locale.ROOT, "  transactions        %,d committed, %,d rolled back: %.1f per checkout%n",
                        commits, rollbacks, (commits + rollbacks) / Math.max(1.0, checkouts.sum()));
            }
            topStatements(pool);
        }
    }

    /** The ledger, counting what the loop asks of it while the measurement runs. */
    private static final class Counting implements Ledger {

        private final Ledger inner;
        private final LongAdder loads = new LongAdder();
        private final LongAdder decided = new LongAdder();
        private final LongAdder conflicts = new LongAdder();
        private volatile boolean measuring;

        private Counting(Ledger inner) {
            this.inner = inner;
        }

        @Override
        public Snapshot load(Command command, Instant now, int reclaimLimit) {
            if (measuring) {
                loads.increment();
            }
            return inner.load(command, now, reclaimLimit);
        }

        @Override
        public boolean apply(Decision decision) {
            boolean applied = inner.apply(decision);
            if (measuring) {
                decided.increment();
                if (!applied) {
                    conflicts.increment();
                }
            }
            return applied;
        }
    }

    /** The database's transaction counts, which can be read but not reset. */
    private record Counters(long commits, long rollbacks) {

        static Counters read(DataSource pool) throws SQLException {
            try (Connection connection = pool.getConnection();
                    Statement statement = connection.createStatement();
                    ResultSet rows = statement.executeQuery(
                            "select xact_commit, xact_rollback from pg_stat_database where datname = current_database()")) {
                rows.next();
                return new Counters(rows.getLong(1), rows.getLong(2));
            }
        }
    }

    private static void resetStatements(DataSource pool) {
        try (Connection connection = pool.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("create extension if not exists pg_stat_statements");
            statement.execute("select pg_stat_statements_reset()");
        } catch (SQLException e) {
            // A server without the module: the run is still measured, only not statement by statement.
        }
    }

    /** The statements the measurement spent the database's time on, if the server keeps statistics on them. */
    private static void topStatements(DataSource pool) {
        try (Connection connection = pool.getConnection();
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(
                        "select calls, rows, round(total_exec_time) as total_ms, round(mean_exec_time::numeric, 3) as mean_ms, "
                                + "left(regexp_replace(query, '\\s+', ' ', 'g'), 90) as query from pg_stat_statements "
                                + "where dbid = (select oid from pg_database where datname = current_database()) "
                                + "order by total_exec_time desc limit 12")) {
            System.out.println("  what the database spent its time on:");
            while (rows.next()) {
                System.out.printf(Locale.ROOT, "    %,9d ms %,9d calls %,9d rows %8.3f ms  %s%n", rows.getLong("total_ms"),
                        rows.getLong("calls"), rows.getLong("rows"), rows.getDouble("mean_ms"), rows.getString("query"));
            }
        } catch (SQLException e) {
            System.out.println("  (no pg_stat_statements on this server: " + e.getMessage() + ")");
        }
    }

    private static long[] join(Future<long[]> future) {
        try {
            return future.get();
        } catch (Exception e) {
            throw new IllegalStateException("a caller failed", e);
        }
    }

    private static double percentile(long[] sorted, double p) {
        if (sorted.length == 0) {
            return Double.NaN;
        }
        return sorted[Math.min(sorted.length - 1, (int) Math.ceil(p * sorted.length) - 1)] / 1e6;
    }
}
