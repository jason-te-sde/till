package io.till.jdbc;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.till.core.BatchDecision;
import io.till.core.BatchSnapshot;
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
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
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
 * before the run (ADR 9). {@code -Dbench.batch=64} puts every command through a queue and one worker
 * that runs whatever has queued, up to that many, as one batch, as the server's batcher does (ADR 16);
 * the decisions and conflicts it reports are then batch writes. The tables are analyzed once, when
 * the warmup ends, as a database running for longer than a run would have had them analyzed.
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
        int batch = Integer.getInteger("bench.batch", 0);

        PostgresFixture.dataSource();
        PostgresFixture.reset();
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(TestDatabase.url());
        config.setUsername(TestDatabase.username());
        config.setPassword(TestDatabase.password());
        config.setMaximumPoolSize(poolSize);
        config.setPoolName("till-bench");
        try (HikariDataSource pool = new HikariDataSource(config)) {
            Wire wire = new Wire();
            Counting ledger = new Counting(new JdbcLedger(wire.over(pool)));
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

            Batcher batcher = batch > 0 ? new Batcher(till, batch) : null;
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
                                Outcome held = run(till, batcher,
                                        new Command.Reserve(IdempotencyKey.of("c-" + n), id, List.of(new Line(sku, 1)), TTL));
                                if (held.ok()) {
                                    if (random.nextInt(10) == 0) {
                                        run(till, batcher, new Command.Release(IdempotencyKey.of("x-" + n), id));
                                    } else {
                                        run(till, batcher, new Command.Commit(IdempotencyKey.of("p-" + n), id));
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
                // Analyzed a second before the measurement starts, so the statements planned again
                // afterwards are not planned inside it.
                Thread.sleep(Math.max(0, Duration.ofNanos(measureFrom - System.nanoTime()).toMillis() - 1_000));
                analyze(pool);
                Thread.sleep(Math.max(0, Duration.ofNanos(measureFrom - System.nanoTime()).toMillis()));
                resetStatements(pool);
                before = Counters.read(pool);
                ledger.measuring = true;
                wire.measuring = true;
            }
            ledger.measuring = false;
            wire.measuring = false;
            if (batcher != null) {
                batcher.close();
            }
            // A backend reports its transactions when it goes idle, and waits up to ten seconds to.
            Thread.sleep(11_000);
            Counters after = Counters.read(pool);

            long[] all = latencies.stream().flatMapToLong(f -> Arrays.stream(join(f))).sorted().toArray();
            double perSecond = checkouts.sum() / (double) seconds;
            double commands = ledger.decided.sum();
            System.out.printf(Locale.ROOT, "%nContention: %d callers, %d connections, %d SKUs in %d shards each, %d s measured after %d s, %s%n",
                    callers, poolSize, skuCount, shards, seconds, warmup,
                    batch > 0 ? "in batches of up to " + batch : "one command at a time");
            System.out.printf(Locale.ROOT, "  checkouts           %,d (%.1f a second)%n", checkouts.sum(), perSecond);
            System.out.printf(Locale.ROOT, "  checkout latency    p50 %.1f ms, p99 %.1f ms%n", percentile(all, 0.50), percentile(all, 0.99));
            System.out.printf(Locale.ROOT, "  gave up (conflicts) %,d%n", exhausted.sum());
            System.out.printf(Locale.ROOT, "  decisions applied   %,.0f, of which conflicted %,d (%.1f%%)%n",
                    commands, ledger.conflicts.sum(), 100.0 * ledger.conflicts.sum() / Math.max(1, commands));
            System.out.printf(Locale.ROOT, "  snapshots loaded    %,d%n", ledger.loads.sum());
            if (ledger.batches.sum() > 0) {
                System.out.printf(Locale.ROOT, "  batches written     %,d, of %.1f commands on average%n",
                        ledger.batches.sum(), ledger.batched.sum() / (double) ledger.batches.sum());
            }
            if (before != null && after != null) {
                long commits = after.commits - before.commits;
                long rollbacks = after.rollbacks - before.rollbacks;
                System.out.printf(Locale.ROOT, "  transactions        %,d committed, %,d rolled back: %.1f per checkout%n",
                        commits, rollbacks, (commits + rollbacks) / Math.max(1.0, checkouts.sum()));
            }
            System.out.printf(Locale.ROOT, "  statements sent     %,d: %.1f per checkout, BEGIN, COMMIT and ROLLBACK included%n",
                    wire.statements.sum(), wire.statements.sum() / Math.max(1.0, checkouts.sum()));
            topStatements(pool);
        }
    }

    /** The ledger, counting what the loop asks of it while the measurement runs. */
    private static final class Counting implements Ledger {

        private final Ledger inner;
        private final LongAdder loads = new LongAdder();
        private final LongAdder decided = new LongAdder();
        private final LongAdder conflicts = new LongAdder();
        private final LongAdder batches = new LongAdder();
        private final LongAdder batched = new LongAdder();
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

        @Override
        public BatchSnapshot loadBatch(List<Command> commands) {
            if (measuring) {
                loads.increment();
            }
            return inner.loadBatch(commands);
        }

        @Override
        public boolean applyBatch(BatchDecision decision) {
            boolean applied = inner.applyBatch(decision);
            if (measuring) {
                decided.increment();
                if (!applied) {
                    conflicts.increment();
                } else {
                    batches.increment();
                    batched.add(decision.records().size());
                }
            }
            return applied;
        }
    }

    private static Outcome run(Till till, Batcher batcher, Command command) {
        return batcher == null ? till.execute(command) : batcher.submit(command);
    }

    /** A queue and one worker that runs whatever has queued as one batch, as the server's batcher does. */
    private static final class Batcher implements AutoCloseable {

        private final Till till;
        private final int maxBatch;
        private final BlockingQueue<Pending> queue = new LinkedBlockingQueue<>();
        private final Thread worker;
        private volatile boolean running = true;

        Batcher(Till till, int maxBatch) {
            this.till = till;
            this.maxBatch = maxBatch;
            this.worker = Thread.ofPlatform().daemon().name("bench-batcher").start(this::work);
        }

        Outcome submit(Command command) {
            Pending pending = new Pending(new Till.Call(command, null), new CompletableFuture<>());
            queue.add(pending);
            return pending.answer().join().get();
        }

        private void work() {
            List<Pending> batch = new ArrayList<>(maxBatch);
            while (running) {
                try {
                    Pending first = queue.poll(100, TimeUnit.MILLISECONDS);
                    if (first == null) {
                        continue;
                    }
                    batch.add(first);
                    queue.drainTo(batch, maxBatch - 1);
                    Till.BatchResult result = till.executeAll(batch.stream().map(Pending::call).toList());
                    for (int i = 0; i < batch.size(); i++) {
                        batch.get(i).answer().complete(result.answers().get(i));
                    }
                } catch (InterruptedException e) {
                    return;
                } catch (RuntimeException e) {
                    batch.forEach(pending -> pending.answer().complete(Till.Answer.failed(e)));
                } finally {
                    batch.clear();
                }
            }
        }

        @Override
        public void close() {
            running = false;
            worker.interrupt();
            Pending pending;
            while ((pending = queue.poll()) != null) {
                pending.answer().complete(Till.Answer.failed(new IllegalStateException("the run is over")));
            }
        }

        private record Pending(Till.Call call, CompletableFuture<Till.Answer> answer) {}
    }

    /**
     * The ledger's pool, counting the statements sent over it while the measurement runs: each one
     * executed, each row of a batch (the server runs every one), each commit and rollback, and each
     * transaction begun — {@code setAutoCommit(false)}, which the driver sends as a {@code BEGIN}
     * ahead of the next statement. The server's own count would leave out a statement that failed,
     * and {@code pg_stat_statements} is not loaded on the container this benchmark starts.
     */
    private static final class Wire {

        private final LongAdder statements = new LongAdder();
        private volatile boolean measuring;

        DataSource over(DataSource pool) {
            return proxy(DataSource.class, pool, (method, args, result) -> {
                if (!method.getName().equals("getConnection")) {
                    return result;
                }
                return proxy(Connection.class, (Connection) result, (call, callArgs, made) -> {
                    switch (call.getName()) {
                        case "setAutoCommit" -> count(Boolean.FALSE.equals(callArgs[0]) ? 1 : 0);
                        case "commit", "rollback" -> count(1);
                        default -> {}
                    }
                    return made instanceof Statement statement ? counted(statement) : made;
                });
            });
        }

        private Statement counted(Statement statement) {
            AtomicLong batched = new AtomicLong();
            After counting = (method, args, result) -> {
                switch (method.getName()) {
                    case "addBatch" -> batched.incrementAndGet();
                    case "clearBatch" -> batched.set(0);
                    case "executeBatch", "executeLargeBatch" -> count(batched.getAndSet(0));
                    case "execute", "executeQuery", "executeUpdate", "executeLargeUpdate" -> count(1);
                    default -> {}
                }
                return result;
            };
            return statement instanceof PreparedStatement prepared
                    ? proxy(PreparedStatement.class, prepared, counting)
                    : proxy(Statement.class, statement, counting);
        }

        private void count(long sent) {
            if (measuring) {
                statements.add(sent);
            }
        }

        @FunctionalInterface
        private interface After {
            Object handle(Method method, Object[] args, Object result) throws Throwable;
        }

        /** {@code target}, with {@code after} seeing every call it answered and what it answered. */
        private static <T> T proxy(Class<T> type, T target, After after) {
            return type.cast(Proxy.newProxyInstance(
                    ContentionBenchmark.class.getClassLoader(), new Class<?>[] {type}, (self, method, args) -> {
                        Object result;
                        try {
                            result = method.invoke(target, args);
                        } catch (InvocationTargetException e) {
                            throw e.getCause();
                        }
                        return after.handle(method, args, result);
                    }));
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

    /**
     * What autovacuum would have done by now on a database that has been running for longer than a
     * run: gathered statistics on the tables the warmup filled. A run starts on a database created for
     * it, and is over before autovacuum first looks at it; until then the planner has no statistics,
     * guesses that each key of an array matches half a percent of a table, and reads a batch's lines
     * by scanning the table instead of looking them up.
     */
    private static void analyze(DataSource pool) throws SQLException {
        try (Connection connection = pool.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("analyze till_stock, till_reservation, till_reservation_line, till_idempotency, till_outbox");
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
