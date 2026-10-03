package io.till.jdbc;

import io.till.core.Command;
import io.till.core.IdempotencyKey;
import io.till.core.Line;
import io.till.core.ReservationId;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.function.ToDoubleFunction;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceLock;

/**
 * What the lean snapshot statement costs the database, on the data a load test leaves behind: the
 * statement {@link JdbcLedger} prepares for every reserve, commit and release, timed against the one
 * it replaced, execution by execution, as a prepared statement on a connection that has run it before.
 *
 * <p>Not part of the build. It seeds 32 SKUs in 16 shards each, 100,000 reservations with 120,000
 * lines and 170,000 idempotency records, which is about what one run of the load test leaves, then
 * for a reserve's load (a hold that does not exist, one SKU) and a commit's (a hold that does, with
 * its lines and every shard of their SKUs) runs each statement in rounds, one after the other in a
 * different order every round, so that what the machine does meanwhile lands on all of them.
 * Against a server that keeps statistics on its statements:
 *
 * <pre>
 * docker run -d --rm --name till-snapshot-bench --cpus 2 --memory 1g -p 55441:5432 \
 *     -e POSTGRES_USER=till -e POSTGRES_PASSWORD=till postgres:17-alpine \
 *     -c shared_preload_libraries=pg_stat_statements -c pg_stat_statements.track_planning=on \
 *     -c max_connections=200
 * TILL_TEST_DB_URL=jdbc:postgresql://localhost:55441/postgres mvn -q -pl till-jdbc -am test \
 *     -Dtest=SnapshotBenchmark -Dsurefire.failIfNoSpecifiedTests=false -Dtill.benchmark=true \
 *     -Dbench.container=till-snapshot-bench
 * </pre>
 *
 * <p>For each statement it prints what the client saw (which includes the network, and on a laptop
 * most of the time), and from {@code pg_stat_statements}, when the server has it, what the server
 * spent: executing, planning, and how many pages it touched. Naming the container with {@code
 * -Dbench.container} adds the CPU time its backend spent a call, read from {@code /proc}, which is
 * the number that counts for a server whose CPU is the limit: executing leaves out starting and
 * ending the plan, which is where a statement with many nodes pays for them. {@code plans} is how
 * many calls in a thousand were planned afresh. A statement that is planned every time has not
 * settled on the generic plan a long-lived connection ends up with, and costs more than it looks.
 * Then it prints the generic plan of each, with {@code EXPLAIN (ANALYZE, BUFFERS)}.
 *
 * <p>{@code -Dbench.statement=a.sql,b.sql} measures more: the first line of each says which
 * parameters it takes, in the order they appear, as {@code -- binds: t k t s}, where {@code t} is the
 * target reservation, {@code k} the idempotency key and {@code s} the SKUs. Each must return the rows
 * the current statement returns, which the run checks before it times anything, unless its file says
 * {@code -- unchecked}: a part of the statement, or a statement that does nothing, is a way of finding
 * what is left when the rest is taken away.
 *
 * <p>{@code bench.skus}, {@code bench.shards}, {@code bench.reservations}, {@code bench.records},
 * {@code bench.executions} (a round, per statement and per load), {@code bench.warmup} and {@code
 * bench.rounds} change the shape; {@code bench.lines} is how many SKUs a reserve's load names (one; a
 * store's order may name twenty); and {@code bench.churn} runs that many stock updates a second
 * alongside the measurement, which is what the load test does to a table of this size.
 */
@EnabledIfSystemProperty(named = "till.benchmark", matches = "true")
@ResourceLock("till-database")
@ExtendWith(RequiresDatabase.class)
class SnapshotBenchmark {

    /**
     * The statement ADR 12 shipped, kept as it was so that there is something to measure the current
     * one against: a {@code scope} CTE of the command's SKUs and the reservation's, deduplicated with
     * {@code UNION}, a window function numbering the lines and the stock, and an {@code ORDER BY} over
     * all of it. Parameters: target, SKUs, key, target.
     */
    private static final String FIRST_SHIPPED =
            "with target_lines as ("
                    + "  select sku, shard, quantity from till_reservation_line where reservation_id = ?"
                    + "), scope as ("
                    + "  select sku from unnest(?::varchar[]) as sku"
                    + "  union"
                    + "  select sku from target_lines"
                    + ") "
                    + "select kind, text1, text2, text3, int1, num1, num2, num3, ts1, ts2 from ("
                    + "  select 'record' as kind, idem_key::text as text1, fingerprint::text as text2, "
                    + "         outcome::text as text3, null::integer as int1, null::bigint as num1, "
                    + "         null::bigint as num2, null::bigint as num3, recorded_at as ts1, "
                    + "         null::timestamptz as ts2, 0::bigint as seq "
                    + "  from till_idempotency where idem_key = ? "
                    + "  union all "
                    + "  select 'reservation', id::text, idem_key::text, state::text, null::integer, "
                    + "         version, null::bigint, null::bigint, created_at, expires_at, 0::bigint "
                    + "  from till_reservation where id = ? "
                    + "  union all "
                    + "  select 'line', sku::text, null::text, null::text, shard, quantity, null::bigint, "
                    + "         null::bigint, null::timestamptz, null::timestamptz, "
                    + "         row_number() over (order by sku, shard) "
                    + "  from target_lines "
                    + "  union all "
                    + "  select 'stock', sku::text, null::text, null::text, shard, on_hand, reserved, version, "
                    + "         null::timestamptz, null::timestamptz, "
                    + "         row_number() over (order by sku collate \"C\", shard) "
                    + "  from till_stock where sku in (select sku from scope)"
                    + ") combined "
                    + "order by kind, seq";

    private static final int SKUS = Integer.getInteger("bench.skus", 32);
    private static final int SHARDS = Integer.getInteger("bench.shards", 16);
    private static final int RESERVATIONS = Integer.getInteger("bench.reservations", 100_000);
    private static final int RECORDS = Integer.getInteger("bench.records", 170_000);
    private static final int EXECUTIONS = Integer.getInteger("bench.executions", 3000);
    private static final int WARMUP = Integer.getInteger("bench.warmup", 2000);
    private static final int ROUNDS = Integer.getInteger("bench.rounds", 5);
    private static final int LINES = Integer.getInteger("bench.lines", 1);
    private static final int CHURN = Integer.getInteger("bench.churn", 0);
    private static final long SEED = Long.getLong("bench.seed", 42);

    /**
     * A statement to measure, and which of a load's inputs fill its parameters, in the order they appear.
     * {@code checked} is false for one that returns other rows on purpose, a part of the statement say.
     */
    private record Candidate(String name, String sql, String binds, boolean checked) {}

    /** What one load is asked: a reservation, the SKUs the command names, and a key. */
    private record Inputs(String target, String[] skus, String key) {}

    @FunctionalInterface
    private interface Load {
        Inputs next(Random random);
    }

    /** What the server and the client reported for one statement, per call. */
    private record Cost(double clientMicros, double execMicros, double planMicros, double plansPerThousand, double pagesHit, double cpuMicros) {}

    @Test
    void leanLoads() throws Exception {
        PostgresFixture.dataSource();
        PostgresFixture.reset();
        try (Connection admin = connect()) {
            seed(admin);
        }

        List<Candidate> statements = new ArrayList<>();
        statements.add(new Candidate("current", currentStatement(), "tkts", true));
        statements.add(new Candidate("first-shipped", FIRST_SHIPPED, "tskt", true));
        String files = System.getProperty("bench.statement");
        if (files != null) {
            for (String file : files.split(",")) {
                statements.add(fromFile(Path.of(file.strip())));
            }
        }

        try (Connection c = connect(); Connection explainer = connect()) {
            boolean tracked = statementsAreTracked(c);
            int backend = backendPid(c);
            List<String> held = sampleReservations(c, 2000);
            System.out.printf(Locale.ROOT, "%nseed %d; statements: %s%n", SEED, statements.stream().map(Candidate::name).toList());
            verifySameRows(c, statements, held);
            AtomicBoolean stopChurn = new AtomicBoolean();
            AtomicLong churned = new AtomicLong();
            Thread churn = null;
            if (CHURN > 0) {
                churn = Thread.ofPlatform().daemon().name("churn").start(() -> churn(stopChurn, churned));
            }

            Map<String, Load> loads = new LinkedHashMap<>();
            loads.put("reserve", random -> new Inputs(UUID.randomUUID().toString(), someSkus(random, LINES), newKey("order")));
            loads.put("commit", random -> new Inputs(held.get(random.nextInt(held.size())), new String[0], newKey("pay")));

            Random random = new Random(SEED);
            for (Load load : loads.values()) {
                for (Candidate statement : statements) {
                    run(c, statement, load, WARMUP, random);
                }
            }

            Map<String, List<Cost>> rounds = new LinkedHashMap<>();
            Map<String, List<long[]>> latencies = new LinkedHashMap<>();
            for (int round = 0; round < ROUNDS; round++) {
                for (Map.Entry<String, Load> load : loads.entrySet()) {
                    List<Candidate> order = new ArrayList<>(statements);
                    Collections.shuffle(order, random);
                    for (Candidate statement : order) {
                        double[] before = tracked ? counters(c, statement) : new double[5];
                        long cpuBefore = backendCpuNanos(backend);
                        long[] times = run(c, statement, load.getValue(), EXECUTIONS, random);
                        long cpuAfter = backendCpuNanos(backend);
                        double[] after = tracked ? counters(c, statement) : new double[5];
                        double calls = after[0] - before[0];
                        String key = load.getKey() + "/" + statement.name();
                        rounds.computeIfAbsent(key, k -> new ArrayList<>()).add(new Cost(
                                Arrays.stream(times).average().orElse(Double.NaN) / 1000.0,
                                tracked ? (after[1] - before[1]) / calls * 1000.0 : Double.NaN,
                                tracked ? (after[3] - before[3]) / calls * 1000.0 : Double.NaN,
                                tracked ? (after[2] - before[2]) / calls * 1000.0 : Double.NaN,
                                tracked ? (after[4] - before[4]) / calls : Double.NaN,
                                cpuBefore < 0 ? Double.NaN : (cpuAfter - cpuBefore) / 1000.0 / times.length));
                        latencies.computeIfAbsent(key, k -> new ArrayList<>()).add(times);
                    }
                }
            }

            stopChurn.set(true);
            if (churn != null) {
                churn.join();
                System.out.printf(Locale.ROOT, "%n%,d stock updates ran alongside, about %,d a second%n", churned.get(), CHURN);
            }
            System.out.printf(Locale.ROOT, "%n%d rounds of %,d executions each, per load and statement%n", ROUNDS, EXECUTIONS);
            System.out.printf(Locale.ROOT, "%-22s %10s %9s %9s %9s %9s %10s %9s %9s%n", "load/statement", "client us", "p50 us",
                    "p99 us", "exec us", "plan us", "plans/1000", "pages", "cpu us");
            for (Map.Entry<String, List<Cost>> entry : rounds.entrySet()) {
                List<Cost> costs = entry.getValue();
                long[] all = latencies.get(entry.getKey()).stream().flatMapToLong(Arrays::stream).sorted().toArray();
                System.out.printf(Locale.ROOT, "%-22s %10.1f %9.1f %9.1f %9.1f %9.1f %10.1f %9.1f %9.1f%n", entry.getKey(),
                        mean(costs, Cost::clientMicros), all[all.length / 2] / 1000.0, all[(int) (all.length * 0.99)] / 1000.0,
                        mean(costs, Cost::execMicros), mean(costs, Cost::planMicros), mean(costs, Cost::plansPerThousand),
                        mean(costs, Cost::pagesHit), mean(costs, Cost::cpuMicros));
            }
            if (!tracked) {
                System.out.println("  (no pg_stat_statements on this server: exec, plan, plans and pages are not measured)");
            }

            for (Candidate statement : statements) {
                for (Map.Entry<String, Load> load : loads.entrySet()) {
                    explainGenericPlan(explainer, statement, load.getValue().next(random), load.getKey());
                }
            }
        }
    }

    private static double mean(List<Cost> costs, ToDoubleFunction<Cost> field) {
        return costs.stream().mapToDouble(field).average().orElse(Double.NaN);
    }

    private static String sku(int n) {
        return String.format(Locale.ROOT, "game-%02d", n);
    }

    /** {@code count} different SKUs, or all of them if there are not that many. */
    private static String[] someSkus(Random random, int count) {
        List<String> all = new ArrayList<>();
        for (int i = 0; i < SKUS; i++) {
            all.add(sku(i));
        }
        Collections.shuffle(all, random);
        return all.subList(0, Math.min(count, SKUS)).toArray(new String[0]);
    }

    /** A key the way the store makes one: a purpose and a SHA-256, so as long and as scattered. */
    private static String newKey(String purpose) {
        return "store." + purpose + "." + UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(TestDatabase.url(), TestDatabase.username(), TestDatabase.password());
    }

    // ---- the data ----

    private static void seed(Connection c) throws SQLException {
        try (Statement s = c.createStatement()) {
            long started = System.nanoTime();
            try {
                s.execute("create extension if not exists pg_stat_statements");
            } catch (SQLException e) {
                // A server that does not preload it: the run is still timed, not statement by statement.
            }
            s.execute("insert into till_stock (sku, shard, on_hand, reserved, version) "
                    + "select 'game-' || lpad(g::text, 2, '0'), sh, 100000000, 0, 0 "
                    + "from generate_series(0, " + (SKUS - 1) + ") g cross join generate_series(0, " + (SHARDS - 1) + ") sh");
            s.execute("create temp table seeded as select n, gen_random_uuid()::text as id, "
                    + "'store.order.' || md5(random()::text || n::text) || md5(n::text || random()::text) as idem_key "
                    + "from generate_series(1, " + RESERVATIONS + ") n");
            s.execute("insert into till_reservation (id, idem_key, state, created_at, expires_at, version) "
                    + "select id, idem_key, (array['HELD', 'COMMITTED', 'COMMITTED', 'COMMITTED', 'RELEASED'])[1 + n % 5], "
                    + "now() - interval '2 hours' + n * interval '50 ms', "
                    + "now() - interval '2 hours' + n * interval '50 ms' + interval '15 minutes', 1 from seeded");
            // A hold is one line, and one in five took from a second shard too: 1.2 lines a hold.
            s.execute("insert into till_reservation_line (reservation_id, sku, shard, quantity) "
                    + "select id, 'game-' || lpad((n % " + SKUS + ")::text, 2, '0'), n % " + SHARDS + ", 1 + n % 3 from seeded "
                    + "union all "
                    + "select id, 'game-' || lpad((n % " + SKUS + ")::text, 2, '0'), (n + 1) % " + SHARDS + ", 1 "
                    + "from seeded where n % 5 = 0");
            int others = Math.max(0, RECORDS - RESERVATIONS);
            int payments = others * 6 / 7;
            s.execute("insert into till_idempotency (idem_key, fingerprint, outcome, recorded_at) "
                    + "select idem_key, md5(idem_key) || md5(idem_key || 'x'), '{\"type\":\"Reserved\",\"id\":\"' || id || '\"}', now() from seeded "
                    + "union all "
                    + "select 'store.pay.' || md5(random()::text || g::text) || md5(g::text || random()::text), "
                    + "md5(g::text) || md5(g::text || 'y'), '{\"type\":\"Committed\"}', now() from generate_series(1, " + payments + ") g "
                    + "union all "
                    + "select 'store.cancel.' || md5(random()::text || g::text) || md5(g::text || random()::text), "
                    + "md5(g::text) || md5(g::text || 'z'), '{\"type\":\"Released\"}', now() "
                    + "from generate_series(1, " + (others - payments) + ") g");
            s.execute("vacuum (analyze) till_stock, till_reservation, till_reservation_line, till_idempotency");
            s.execute("checkpoint");
            try (ResultSet rs = s.executeQuery(
                    "select (select count(*) from till_stock), (select count(*) from till_reservation), "
                            + "(select count(*) from till_reservation_line), (select count(*) from till_idempotency), "
                            + "pg_size_pretty(pg_total_relation_size('till_reservation') + pg_total_relation_size('till_reservation_line') "
                            + "+ pg_total_relation_size('till_idempotency') + pg_total_relation_size('till_stock'))")) {
                rs.next();
                System.out.printf(Locale.ROOT, "%nseeded in %.1f s: %,d stock rows, %,d reservations, %,d lines, %,d records, %s%n",
                        (System.nanoTime() - started) / 1e9, rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getString(5));
            }
        }
    }

    private static List<String> sampleReservations(Connection c, int n) throws SQLException {
        List<String> ids = new ArrayList<>();
        try (Statement s = c.createStatement();
                ResultSet rs = s.executeQuery("select id from till_reservation order by random() limit " + n)) {
            while (rs.next()) {
                ids.add(rs.getString(1));
            }
        }
        return ids;
    }

    // ---- the statements ----

    /** The statement {@link JdbcLedger} prepares for a lean load, caught as it goes by. */
    private static String currentStatement() {
        List<String> prepared = new ArrayList<>();
        DataSource real = PostgresFixture.dataSource();
        DataSource recording = proxy(DataSource.class, (method, args) -> {
            Object result = forward(real, method, args);
            if (!method.getName().equals("getConnection")) {
                return result;
            }
            Connection connection = (Connection) result;
            return proxy(Connection.class, (call, callArgs) -> {
                if (call.getName().equals("prepareStatement")) {
                    prepared.add((String) callArgs[0]);
                }
                return forward(connection, call, callArgs);
            });
        });
        Command command = new Command.Reserve(IdempotencyKey.of("k-capture"), ReservationId.of("r-capture"),
                List.of(Line.of("game-00", 1)), Duration.ofMinutes(15));
        new JdbcLedger(recording).load(command, Instant.now(), 0);
        if (prepared.size() != 1) {
            throw new IllegalStateException("a lean load should prepare one statement, and prepared " + prepared);
        }
        return prepared.get(0);
    }

    private static Candidate fromFile(Path file) throws IOException {
        String binds = null;
        boolean checked = true;
        StringBuilder sql = new StringBuilder();
        for (String line : Files.readString(file).split("\n")) {
            if (line.startsWith("-- binds:")) {
                binds = line.substring("-- binds:".length()).replace(" ", "");
            } else if (line.startsWith("-- unchecked")) {
                checked = false;
            } else if (!line.strip().startsWith("--")) {
                sql.append(line).append('\n');
            }
        }
        if (binds == null) {
            throw new IllegalArgumentException(file + " must say which parameters it takes, as a first line like '-- binds: t k t s'");
        }
        return new Candidate(file.getFileName().toString().replaceAll("\\.sql$", ""), sql.toString().strip(), binds, checked);
    }

    private static void bind(PreparedStatement statement, Connection c, String binds, Inputs inputs) throws SQLException {
        for (int i = 0; i < binds.length(); i++) {
            switch (binds.charAt(i)) {
                case 't' -> statement.setString(i + 1, inputs.target());
                case 's' -> statement.setArray(i + 1, c.createArrayOf("varchar", inputs.skus()));
                case 'k' -> statement.setString(i + 1, inputs.key());
                default -> throw new IllegalArgumentException("a parameter is t, k or s, not " + binds.charAt(i));
            }
        }
    }

    /** The statement's text as sent, tagged so that its row in {@code pg_stat_statements} can be found. */
    private static String tagged(Candidate statement) {
        return "/* bench:" + statement.name() + " */ " + statement.sql();
    }

    private static long[] run(Connection c, Candidate statement, Load load, int executions, Random random) throws SQLException {
        long[] times = new long[executions];
        String sql = tagged(statement);
        for (int i = 0; i < executions; i++) {
            Inputs inputs = load.next(random);
            long started = System.nanoTime();
            // Prepared afresh each time, as a load does: the driver gives the same server-side
            // statement back once it has seen the text a few times.
            try (PreparedStatement prepared = c.prepareStatement(sql)) {
                bind(prepared, c, statement.binds(), inputs);
                try (ResultSet rows = prepared.executeQuery()) {
                    int columns = rows.getMetaData().getColumnCount();
                    while (rows.next()) {
                        for (int column = 1; column <= columns; column++) {
                            rows.getString(column);
                        }
                    }
                }
            }
            times[i] = System.nanoTime() - started;
        }
        return times;
    }

    private static List<String> rowsOf(Connection c, Candidate statement, Inputs inputs) throws SQLException {
        List<String> rows = new ArrayList<>();
        try (PreparedStatement prepared = c.prepareStatement(tagged(statement))) {
            bind(prepared, c, statement.binds(), inputs);
            try (ResultSet result = prepared.executeQuery()) {
                int columns = result.getMetaData().getColumnCount();
                while (result.next()) {
                    StringBuilder row = new StringBuilder();
                    for (int column = 1; column <= columns; column++) {
                        row.append(result.getString(column)).append('|');
                    }
                    rows.add(row.toString());
                }
            }
        }
        Collections.sort(rows);
        return rows;
    }

    /**
     * Every statement returns the rows the first one does, as a set: for holds that exist and ones
     * that do not, keys that were used and ones that were not, SKUs that are there and one that is
     * not, and a command that names SKUs its hold also has. A statement that is quicker because it
     * answers a different question is not quicker.
     */
    private static void verifySameRows(Connection c, List<Candidate> statements, List<String> held) throws SQLException {
        Random random = new Random(SEED);
        List<Inputs> cases = new ArrayList<>();
        cases.add(new Inputs("nowhere", new String[0], "store.order.nowhere"));
        for (int i = 0; i < 200; i++) {
            String id = held.get(random.nextInt(held.size()));
            String sku = sku(random.nextInt(SKUS));
            cases.add(new Inputs(id, new String[0], "store.pay.unused-" + i));
            cases.add(new Inputs("nowhere-" + i, new String[] {sku}, "store.order.unused-" + i));
            cases.add(new Inputs("nowhere-" + i, new String[] {sku, sku(random.nextInt(SKUS)), "ghost-" + i}, "store.order.unused-" + i));
            cases.add(new Inputs(id, new String[] {firstSkuOf(c, id)}, keyOf(c, id)));
            cases.add(new Inputs(id, new String[] {firstSkuOf(c, id), sku}, "store.order.unused-" + i));
            cases.add(new Inputs("nowhere-" + i, someSkus(random, 20), "store.order.unused-" + i));
        }
        for (Inputs inputs : cases) {
            List<String> expected = rowsOf(c, statements.get(0), inputs);
            for (Candidate statement : statements.subList(1, statements.size())) {
                if (!statement.checked()) {
                    continue;
                }
                List<String> actual = rowsOf(c, statement, inputs);
                if (!expected.equals(actual)) {
                    throw new AssertionError(statement.name() + " returns other rows than " + statements.get(0).name() + " for target "
                            + inputs.target() + ", SKUs " + Arrays.toString(inputs.skus()) + " and key " + inputs.key()
                            + "\n  " + statements.get(0).name() + ": " + expected + "\n  " + statement.name() + ": " + actual);
                }
            }
        }
        System.out.printf(Locale.ROOT, "every statement returns the same rows for %d sets of inputs%n", cases.size());
    }

    private static String firstSkuOf(Connection c, String reservation) throws SQLException {
        return single(c, "select min(sku) from till_reservation_line where reservation_id = ?", reservation);
    }

    private static String keyOf(Connection c, String reservation) throws SQLException {
        return single(c, "select idem_key from till_reservation where id = ?", reservation);
    }

    private static String single(Connection c, String sql, String parameter) throws SQLException {
        try (PreparedStatement statement = c.prepareStatement(sql)) {
            statement.setString(1, parameter);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getString(1);
            }
        }
    }

    // ---- what the server says ----

    private static boolean statementsAreTracked(Connection c) {
        try (Statement s = c.createStatement()) {
            s.execute("select pg_stat_statements_reset()");
            return true;
        } catch (SQLException e) {
            return false;
        }
    }

    /** Calls, executing ms, plans, planning ms and pages found in the cache, for one statement, so far. */
    private static double[] counters(Connection c, Candidate statement) throws SQLException {
        try (PreparedStatement prepared = c.prepareStatement(
                "select coalesce(sum(calls), 0), coalesce(sum(total_exec_time), 0), coalesce(sum(plans), 0), "
                        + "coalesce(sum(total_plan_time), 0), coalesce(sum(shared_blks_hit), 0) "
                        + "from pg_stat_statements where query like ?")) {
            prepared.setString(1, "/* bench:" + statement.name() + " */%");
            try (ResultSet rows = prepared.executeQuery()) {
                rows.next();
                return new double[] {rows.getDouble(1), rows.getDouble(2), rows.getDouble(3), rows.getDouble(4), rows.getDouble(5)};
            }
        }
    }

    private static int backendPid(Connection c) throws SQLException {
        try (Statement s = c.createStatement();
                ResultSet rows = s.executeQuery("select pg_backend_pid()")) {
            rows.next();
            return rows.getInt(1);
        }
    }

    /** The CPU time a server process has used, in nanoseconds, or -1 when no container is named. */
    private static long backendCpuNanos(int pid) {
        String container = System.getProperty("bench.container");
        if (container == null) {
            return -1;
        }
        try {
            Process process = new ProcessBuilder("docker", "exec", container, "cat", "/proc/" + pid + "/schedstat").start();
            String schedstat = new String(process.getInputStream().readAllBytes()).trim();
            process.waitFor();
            return Long.parseLong(schedstat.split(" ")[0]);
        } catch (IOException e) {
            throw new IllegalStateException("could not read the CPU time of backend " + pid + " in " + container, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /**
     * The plan a prepared statement settles on, which does not know the values it is run with, and
     * what it did on a run: prepared by hand and run a few times, then explained.
     */
    private static void explainGenericPlan(Connection c, Candidate statement, Inputs inputs, String load) throws SQLException {
        StringBuilder numbered = new StringBuilder();
        int parameters = 0;
        for (char ch : statement.sql().toCharArray()) {
            numbered.append(ch == '?' ? "$" + ++parameters : String.valueOf(ch));
        }
        StringBuilder types = new StringBuilder();
        StringBuilder values = new StringBuilder();
        for (int i = 0; i < statement.binds().length(); i++) {
            String separator = i == 0 ? "" : ", ";
            switch (statement.binds().charAt(i)) {
                case 't' -> {
                    types.append(separator).append("varchar");
                    values.append(separator).append('\'').append(inputs.target()).append('\'');
                }
                case 's' -> {
                    types.append(separator).append("varchar[]");
                    values.append(separator).append("'{").append(String.join(",", inputs.skus())).append("}'");
                }
                default -> {
                    types.append(separator).append("varchar");
                    values.append(separator).append('\'').append(inputs.key()).append('\'');
                }
            }
        }
        try (Statement s = c.createStatement()) {
            s.execute("deallocate all");
            s.execute("set plan_cache_mode = force_generic_plan");
            s.execute("prepare bench_plan " + (types.isEmpty() ? "" : "(" + types + ") ") + "as " + numbered);
            System.out.printf(Locale.ROOT, "%n==== %s, a %s's load: generic plan ====%n", statement.name(), load);
            for (int pass = 0; pass < 7; pass++) {
                // The last of several: the first runs bring the pages in, and the last is what is shown.
                boolean shown = pass == 6;
                String explain = shown ? "explain (analyze, buffers) " : "explain (analyze, buffers, timing off) ";
                try (ResultSet rows = s.executeQuery(explain + "execute bench_plan" + (values.isEmpty() ? "" : "(" + values + ")"))) {
                    while (rows.next()) {
                        if (shown) {
                            System.out.println(rows.getString(1));
                        }
                    }
                }
            }
        }
    }

    /**
     * Stock updates at a steady rate, each in a transaction of its own, as the load test's checkouts
     * make them: a table of a few hundred rows that every command writes to is mostly dead versions of
     * them, and a statement that reads all of it pays for each.
     */
    private static void churn(AtomicBoolean stop, AtomicLong count) {
        try (Connection c = connect();
                PreparedStatement update = c.prepareStatement(
                        "update till_stock set on_hand = on_hand + 1, version = version + 1, updated_at = now() "
                                + "where sku = ? and shard = ?")) {
            Random random = new Random(SEED + 1);
            long interval = 1_000_000_000L / CHURN;
            long next = System.nanoTime();
            while (!stop.get()) {
                update.setString(1, sku(random.nextInt(SKUS)));
                update.setInt(2, random.nextInt(SHARDS));
                update.executeUpdate();
                count.incrementAndGet();
                next += interval;
                long wait = next - System.nanoTime();
                if (wait > 0) {
                    LockSupport.parkNanos(wait);
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("the stock updates failed", e);
        }
    }

    // ---- plumbing ----

    @FunctionalInterface
    private interface Handler {
        Object handle(Method method, Object[] args) throws Throwable;
    }

    private static <T> T proxy(Class<T> type, Handler handler) {
        return type.cast(Proxy.newProxyInstance(
                SnapshotBenchmark.class.getClassLoader(), new Class<?>[] {type}, (self, method, args) -> handler.handle(method, args)));
    }

    private static Object forward(Object target, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }
}
