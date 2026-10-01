package io.till.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.till.core.Command;
import io.till.core.Decision;
import io.till.core.IdempotencyKey;
import io.till.core.Kernel;
import io.till.core.Line;
import io.till.core.Outcome;
import io.till.core.OutboxEntry;
import io.till.core.Reservation;
import io.till.core.ReservationId;
import io.till.core.ReservationState;
import io.till.core.Sku;
import io.till.core.Snapshot;
import io.till.core.StockItem;
import io.till.core.Till;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

// Suites that share the one database run one at a time. Everything else in the build still runs in
// parallel; these truncate the tables they are about, a truncate locks the whole table, and two of
// them at once is a deadlock rather than a race.
@ResourceLock("till-database")
@ExtendWith(RequiresDatabase.class)
class JdbcLedgerTest {

    private static final Instant T0 = Instant.parse("2026-09-10T12:00:00Z");
    private static final Duration TTL = Duration.ofMinutes(15);

    private DataSource dataSource;
    private JdbcLedger ledger;
    private Till till;

    /** The same till; named so the listing tests read like the server's own privilege split. */
    private Till admin() {
        return till;
    }

    @BeforeEach
    void setUp() {
        dataSource = PostgresFixture.dataSource();
        PostgresFixture.reset();
        ledger = new JdbcLedger(dataSource);
        till = Till.builder(ledger).clock(Clock.fixed(T0, ZoneOffset.UTC)).build();
    }

    @Test
    @DisplayName("the whole path, against a real database")
    void theHappyPath() {
        till.adjust(key("delivery-41"), sku("widget"), 100);

        Outcome reserved = till.reserve(key("checkout-1"), rid("r1"), List.of(Line.of("widget", 2)), TTL);
        assertInstanceOf(Outcome.Reserved.class, reserved);
        assertEquals(98, ledger.allStock().get(0).available());

        till.commit(key("pay-1"), rid("r1"));

        StockItem after = ledger.allStock().get(0);
        assertEquals(98, after.onHand());
        assertEquals(0, after.reserved());
        assertEquals(ReservationState.COMMITTED, ledger.allReservations().get(0).state());
    }

    @Test
    @DisplayName("an instant survives the round trip through timestamptz exactly")
    void instantsRoundTrip() {
        till.adjust(key("d1"), sku("widget"), 10);
        Outcome outcome = till.reserve(key("c1"), rid("r1"), List.of(Line.of("widget", 1)), TTL);

        Instant promised = ((Outcome.Reserved) outcome).expiresAt();
        assertEquals(promised, ledger.allReservations().get(0).expiresAt());
        assertEquals(T0.plus(TTL), promised);
    }

    @Test
    @DisplayName("a decision made against a version that has moved is refused, and writes nothing")
    void refusesAStaleVersion() {
        till.adjust(key("d1"), sku("widget"), 10);
        Command command = new Command.Reserve(key("k1"), rid("r1"), List.of(Line.of("widget", 1)), TTL);
        Decision stale = Kernel.decide(ledger.load(command, T0, 0), command, T0);

        till.adjust(key("d2"), sku("widget"), 5);

        assertFalse(ledger.apply(stale));
        assertTrue(ledger.allReservations().isEmpty(), "the reservation must not survive the rollback");
        assertEquals(15, ledger.allStock().get(0).onHand());
        assertEquals(
                List.of("adjusted:d1", "adjusted:d2"),
                ledger.allEvents().stream().map(OutboxEntry::dedupeKey).toList(),
                "and neither must its outbox row: only the two adjustments that did happen are there");
    }

    @Test
    @DisplayName("two servers claiming one idempotency key: one writes, the other is told to reload")
    void refusesADuplicateKey() {
        till.adjust(key("d1"), sku("widget"), 10);
        Command command = new Command.Reserve(key("same"), rid("r1"), List.of(Line.of("widget", 1)), TTL);
        Snapshot shared = ledger.load(command, T0, 0);

        Decision first = Kernel.decide(shared, command, T0);
        Command other = new Command.Reserve(key("same"), rid("r2"), List.of(Line.of("widget", 1)), TTL);
        Decision second = Kernel.decide(shared, other, T0);

        assertTrue(ledger.apply(first));
        assertFalse(ledger.apply(second));
        assertEquals(List.of(rid("r1")), ledger.allReservations().stream().map(Reservation::id).toList());
    }

    @Test
    @DisplayName("a commit loads the SKUs the caller never sent")
    void commitScopesToTheReservation() {
        till.adjust(key("d1"), sku("widget"), 10);
        till.adjust(key("d2"), sku("gadget"), 10);
        till.reserve(key("k1"), rid("r1"), List.of(Line.of("widget", 1), Line.of("gadget", 2)), TTL);

        Snapshot snapshot = ledger.load(new Command.Commit(key("k2"), rid("r1")), T0, 32);

        assertEquals(2, snapshot.stock().size());
        assertEquals(2, snapshot.reservation().orElseThrow().lines().size());
    }

    @Test
    @DisplayName("a command reclaims only the expired holds standing in its way")
    void reclaimIsScopedByTheIndexedJoin() {
        till.adjust(key("d1"), sku("widget"), 10);
        till.adjust(key("d2"), sku("gadget"), 10);
        till.reserve(key("k1"), rid("stale-widget"), List.of(Line.of("widget", 10)), Duration.ofMinutes(1));
        till.reserve(key("k2"), rid("stale-gadget"), List.of(Line.of("gadget", 10)), Duration.ofMinutes(1));

        Snapshot snapshot =
                ledger.load(
                        new Command.Reserve(key("k3"), rid("r3"), List.of(Line.of("widget", 1)), TTL),
                        T0.plus(Duration.ofHours(1)),
                        32);

        assertEquals(
                List.of(rid("stale-widget")), snapshot.reclaimable().stream().map(Reservation::id).toList());
    }

    @Test
    @DisplayName("expired stock comes back without a sweep having run")
    void reclaimsOnDemand() {
        Till later = Till.builder(ledger).clock(Clock.fixed(T0.plus(Duration.ofHours(1)), ZoneOffset.UTC)).build();
        till.adjust(key("d1"), sku("widget"), 10);
        till.reserve(key("k1"), rid("r1"), List.of(Line.of("widget", 10)), Duration.ofMinutes(1));

        Outcome outcome = later.reserve(key("k2"), rid("r2"), List.of(Line.of("widget", 10)), TTL);

        assertInstanceOf(Outcome.Reserved.class, outcome);
        assertEquals(
                ReservationState.EXPIRED,
                ledger.allReservations().stream().filter(r -> r.id().equals(rid("r1"))).findFirst().orElseThrow().state());
    }

    @Test
    @DisplayName("the outbox keeps every event in order and remembers what was published")
    void outbox() {
        till.adjust(key("d1"), sku("widget"), 10);
        till.reserve(key("k1"), rid("r1"), List.of(Line.of("widget", 1)), TTL);
        till.commit(key("k2"), rid("r1"));

        List<OutboxEntry> pending = ledger.unpublished(10);
        assertEquals(
                List.of("adjusted:d1", "reserved:r1", "committed:r1"),
                pending.stream().map(OutboxEntry::dedupeKey).toList());

        ledger.markPublished(pending.stream().map(OutboxEntry::sequence).limit(2).toList(), Instant.now());
        assertEquals(List.of("committed:r1"), ledger.unpublished(10).stream().map(OutboxEntry::dedupeKey).toList());
    }

    @Test
    @DisplayName("one publisher at a time: a second finds the claim taken, and a failed publish marks nothing")
    void publishingIsClaimed() throws Exception {
        till.adjust(key("d1"), sku("widget"), 10);
        till.adjust(key("d2"), sku("widget"), 5);

        List<List<Long>> sent = new ArrayList<>();
        List<OptionalInt> meanwhile = new ArrayList<>();
        OptionalInt first = ledger.publishNext(1, T0, batch -> {
            // While this round holds the claim, a round on another thread finds it taken.
            meanwhile.add(elsewhere(() -> ledger.publishNext(10, T0, ignored -> {
                throw new AssertionError("a second publisher sent while the first held the claim");
            })));
            sent.add(batch.stream().map(OutboxEntry::sequence).toList());
        });

        assertEquals(OptionalInt.of(1), first);
        assertEquals(List.of(OptionalInt.empty()), meanwhile);
        assertEquals(List.of(List.of(1L)), sent);
        assertEquals(List.of(2L), ledger.unpublished(10).stream().map(OutboxEntry::sequence).toList());

        assertThrows(IllegalStateException.class, () -> ledger.publishNext(10, T0, batch -> {
            throw new IllegalStateException("the broker is down");
        }));
        assertEquals(List.of(2L), ledger.unpublished(10).stream().map(OutboxEntry::sequence).toList(), "nothing was marked");
        assertEquals(OptionalInt.of(1), ledger.publishNext(10, T0, batch -> {}), "and the claim was given up");
        assertEquals(OptionalInt.of(0), ledger.publishNext(10, T0, batch -> {
            throw new AssertionError("nothing was waiting");
        }));
    }

    /** Runs on another thread, as a second publisher would, and waits for it. */
    private static <T> T elsewhere(java.util.concurrent.Callable<T> work) {
        try (java.util.concurrent.ExecutorService other = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            return other.submit(work).get(30, java.util.concurrent.TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException e) {
            throw new IllegalStateException(e.getCause());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("the database refuses an impossible level even if the application asks for one")
    void theCheckConstraintIsReal() throws SQLException {
        till.adjust(key("d1"), sku("widget"), 10);

        try (var connection = dataSource.getConnection();
                var statement = connection.createStatement()) {
            SQLException thrown =
                    assertThrows(
                            SQLException.class,
                            () -> statement.execute("update till_stock set reserved = 11 where sku = 'widget'"));
            assertEquals("23514", thrown.getSQLState(), thrown.getMessage());
        }
    }

    @Test
    @DisplayName("stock pages by SKU, and the page is keyset-based so it cannot skip a row")
    void listsStockByPage() {
        for (String sku : List.of("aaa", "bbb", "ccc", "ddd")) {
            admin().adjust(key("d-" + sku), sku(sku), 5);
        }

        List<StockItem> first = ledger.listStock(Optional.empty(), 2);
        assertEquals(List.of(sku("aaa"), sku("bbb")), first.stream().map(StockItem::sku).toList());

        List<StockItem> second = ledger.listStock(Optional.of(first.get(1).sku()), 2);
        assertEquals(List.of(sku("ccc"), sku("ddd")), second.stream().map(StockItem::sku).toList());

        assertTrue(ledger.listStock(Optional.of(sku("ddd")), 2).isEmpty());
    }

    @Test
    @DisplayName("reservations list newest first, optionally by state")
    void listsReservationsNewestFirst() {
        admin().adjust(key("d1"), sku("widget"), 100);
        // A clock that moves, so "newest first" is a claim about creation order rather than about
        // whatever order the rows happen to come back in.
        for (int i = 1; i <= 3; i++) {
            Till at = Till.builder(ledger).clock(Clock.fixed(T0.plusSeconds(i), ZoneOffset.UTC)).build();
            at.reserve(key("c" + i), rid("r" + i), List.of(Line.of("widget", 1)), TTL);
        }
        till.commit(key("pay-2"), rid("r2"));

        assertEquals(
                List.of(rid("r3"), rid("r2"), rid("r1")),
                ledger.listReservations(Optional.empty(), 10).stream().map(Reservation::id).toList());
        assertEquals(
                List.of(rid("r3"), rid("r1")),
                ledger.listReservations(Optional.of(ReservationState.HELD), 10).stream()
                        .map(Reservation::id)
                        .toList());
        assertEquals(
                List.of(rid("r2")),
                ledger.listReservations(Optional.of(ReservationState.COMMITTED), 10).stream()
                        .map(Reservation::id)
                        .toList());
    }

    @Test
    @DisplayName("a listed reservation comes back with its lines, in one extra query rather than n")
    void listedReservationsCarryTheirLines() {
        admin().adjust(key("d1"), sku("widget"), 100);
        admin().adjust(key("d2"), sku("gadget"), 100);
        till.reserve(key("c1"), rid("r1"), List.of(Line.of("widget", 2), Line.of("gadget", 3)), TTL);

        Reservation listed = ledger.listReservations(Optional.empty(), 10).get(0);

        assertEquals(List.of(Line.of("gadget", 3), Line.of("widget", 2)), listed.lines());
    }

    @Test
    @DisplayName("a limit beyond the maximum is clamped, and a limit of zero is refused")
    void pageSizeIsBounded() {
        admin().adjust(key("d1"), sku("widget"), 1);

        assertEquals(1, ledger.listStock(Optional.empty(), 10_000).size(), "clamped, not refused");
        assertThrows(IllegalArgumentException.class, () -> ledger.listStock(Optional.empty(), 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> ledger.listReservations(Optional.empty(), -1));
    }

    @Test
    @DisplayName("a reclaiming snapshot is one instant, and loading one leaves the connection's settings alone")
    void aSnapshotIsOneInstant() {
        till.adjust(key("d1"), sku("widget"), 10);
        till.reserve(key("c1"), rid("r1"), List.of(Line.of("widget", 2)), TTL);

        // Only a reclaiming load still takes this path: a lean one (reclaimLimit 0, every reserve,
        // commit and release's first try) is one statement end to end, so there is only one read left
        // to interfere with and this mechanism — interfering between an earlier read and a later one —
        // no longer has a seam. aLeanCommitLoadIsOneStatement below covers that one instead; this test
        // keeps covering the transactional path, which a reclaiming load (and the sweep) still use
        // unchanged, with a reclaim limit standing in for both. A commit's reclaiming snapshot takes
        // five reads: record, reservation, lines, reclaimable, then stock. After the first four,
        // another connection changes the stock and commits; the fifth, which reads the stock, must not
        // see it.
        List<String> settings = new CopyOnWriteArrayList<>();
        List<String> statements = new CopyOnWriteArrayList<>();
        DataSource interfering = interfering(dataSource, settings, statements, "select sku, shard, on_hand", () -> {
            try (Connection other = dataSource.getConnection();
                    Statement statement = other.createStatement()) {
                statement.executeUpdate(
                        "update till_stock set on_hand = on_hand + 5, version = version + 1 where sku = 'widget'");
            }
        });

        Snapshot snapshot = new JdbcLedger(interfering).load(new Command.Commit(key("p1"), rid("r1")), T0, 32);

        assertEquals(10, snapshot.require(sku("widget")).onHand(), "read at the instant the snapshot began");
        assertEquals(15, ledger.stock(sku("widget")).orElseThrow().onHand(), "although the change was made");
        assertEquals(List.of(), settings, "each costs the database a statement, to ask, to set or to put back");
    }

    @Test
    @DisplayName("a lean reserve's load is one statement, with no SET, BEGIN or COMMIT")
    void aLeanReserveLoadIsOneStatement() {
        till.adjust(key("d1"), sku("widget"), 10);

        List<String> settings = new CopyOnWriteArrayList<>();
        List<String> statements = new CopyOnWriteArrayList<>();
        DataSource observed = interfering(dataSource, settings, statements, null, () -> {});

        Command command = new Command.Reserve(key("c1"), rid("r1"), List.of(Line.of("widget", 1)), TTL);
        new JdbcLedger(observed).load(command, T0, 0);

        assertEquals(
                1,
                statements.size(),
                "one statement and nothing to set, begin or commit around it, got " + statements);
        assertTrue(statements.get(0).contains("till_stock"), "must be the snapshot query, got " + statements);
        assertFalse(statements.get(0).contains("set transaction"), "no isolation level either: " + statements);
    }

    @Test
    @DisplayName("a lean commit's load is one statement, with no SET, BEGIN or COMMIT")
    void aLeanCommitLoadIsOneStatement() {
        till.adjust(key("d1"), sku("widget"), 10);
        till.adjust(key("d2"), sku("gadget"), 10);
        till.reserve(key("c1"), rid("r1"), List.of(Line.of("widget", 1), Line.of("gadget", 2)), TTL);

        List<String> settings = new CopyOnWriteArrayList<>();
        List<String> statements = new CopyOnWriteArrayList<>();
        DataSource observed = interfering(dataSource, settings, statements, null, () -> {});

        Command command = new Command.Commit(key("p1"), rid("r1"));
        Snapshot snapshot = new JdbcLedger(observed).load(command, T0, 0);

        assertEquals(
                1,
                statements.size(),
                "one statement and nothing to set, begin or commit around it, got " + statements);
        assertTrue(statements.get(0).contains("till_stock"), "must be the snapshot query, got " + statements);
        assertFalse(statements.get(0).contains("set transaction"), "no isolation level either: " + statements);
        // Not just one statement: the right one, that found what a commit needs — both SKUs, scoped
        // through the reservation it names, although Commit declares none of its own — in that one
        // statement rather than in a second read once the reservation's lines were known.
        assertEquals(2, snapshot.stock().size());
        assertEquals(2, snapshot.reservation().orElseThrow().lines().size());
    }

    @Test
    @DisplayName("one statement's snapshot is one instant even when one of its arms is slow")
    void aSingleStatementSnapshotIsOneInstant() throws Exception {
        till.adjust(key("d1"), sku("widget"), 10);

        // aLeanCommitLoadIsOneStatement proves the real load is one statement; this proves that one
        // statement is still one instant, the property aSnapshotIsOneInstant proves for the
        // transactional path by interfering between two of its reads. A single statement has no such
        // seam from the JDBC side — prepareStatement and executeQuery each run once — so this goes
        // underneath JdbcLedger entirely, to PostgreSQL's own guarantee that every arm of one
        // statement, UNION ALL included, runs against the snapshot taken when the statement began. A
        // first arm takes two seconds to produce its one (unused) row; a second arm reads the stock
        // row a concurrent transaction updates and commits well within those two seconds. If
        // PostgreSQL gave the second arm a fresher snapshot than the first because it physically runs
        // later, this reads 15 instead of 10 — which is exactly the failure this test existed to catch
        // when the two arms were temporarily issued as two separate statements while writing it.
        ExecutorService thread = Executors.newSingleThreadExecutor();
        try {
            Future<Long> slow = thread.submit(() -> {
                try (Connection connection = dataSource.getConnection();
                        Statement guard = connection.createStatement()) {
                    // Belt and suspenders: a parallel worker could otherwise run the second arm in a
                    // process with a snapshot of its own. The table is far too small for the planner
                    // to want one anyway.
                    guard.execute("set max_parallel_workers_per_gather = 0");
                    try (PreparedStatement statement = connection.prepareStatement(
                                    "select null::bigint as on_hand from pg_sleep(2) "
                                            + "union all "
                                            + "select on_hand from till_stock where sku = 'widget'");
                            ResultSet rows = statement.executeQuery()) {
                        long onHand = -1;
                        while (rows.next()) {
                            long value = rows.getLong("on_hand");
                            if (!rows.wasNull()) {
                                onHand = value;
                            }
                        }
                        return onHand;
                    }
                }
            });

            // Generous relative to the statement's own two-second sleep: opening a connection and
            // sending one update on an otherwise idle pool takes milliseconds, not hundreds of them.
            Thread.sleep(500);
            try (Connection other = dataSource.getConnection();
                    Statement update = other.createStatement()) {
                update.executeUpdate(
                        "update till_stock set on_hand = on_hand + 5, version = version + 1 where sku = 'widget'");
            }

            assertEquals(
                    10, slow.get(10, TimeUnit.SECONDS), "the committed update must not reach an arm still running");
        } finally {
            thread.shutdown();
        }
    }

    @Test
    @DisplayName("an empty ledger answers rather than failing")
    void emptyLedger() {
        assertTrue(ledger.listStock(Optional.empty(), 10).isEmpty());
        assertTrue(ledger.listReservations(Optional.empty(), 10).isEmpty());
        assertTrue(ledger.allStock().isEmpty());
        assertTrue(ledger.allReservations().isEmpty());
        assertTrue(ledger.allEvents().isEmpty());
        assertTrue(ledger.unpublished(10).isEmpty());
        assertEquals(0, till.sweep(10));

        Outcome outcome = till.reserve(key("k1"), rid("r1"), List.of(Line.of("ghost", 1)), TTL);
        assertEquals(io.till.core.RejectionCode.UNKNOWN_SKU, ((Outcome.Rejected) outcome).code());
    }

    /** Something done to the database, from the test's side. */
    @FunctionalInterface
    private interface Interference {
        void run() throws SQLException;
    }

    /**
     * A pool whose connections run {@code interference} once, just before preparing the first
     * statement that starts with {@code before} (never, if {@code before} is {@code null}); note
     * every connection setting they are asked for or asked to change into {@code settings}; and
     * record the text of every statement prepared, plus every {@code commit}, {@code rollback} or
     * {@code setAutoCommit} call, into {@code statements}, in order — so that a test can tell how
     * many statements a load issued and whether any of them opened or closed a transaction of its
     * own, without the database itself ever seeing more than one connection's worth of calls change.
     */
    private static DataSource interfering(
            DataSource real, List<String> settings, List<String> statements, String before, Interference interference) {
        AtomicBoolean done = new AtomicBoolean();
        return proxy(DataSource.class, (method, args) -> {
            Object result = forward(real, method, args);
            if (!method.getName().equals("getConnection")) {
                return result;
            }
            Connection connection = (Connection) result;
            return proxy(Connection.class, (call, callArgs) -> {
                switch (call.getName()) {
                    case "getTransactionIsolation", "setTransactionIsolation", "isReadOnly", "setReadOnly" ->
                            settings.add(call.getName());
                    case "commit", "rollback" -> statements.add(call.getName());
                    case "setAutoCommit" -> statements.add("setAutoCommit(" + callArgs[0] + ")");
                    case "prepareStatement" -> {
                        String sql = (String) callArgs[0];
                        statements.add(sql);
                        if (before != null && sql.startsWith(before) && done.compareAndSet(false, true)) {
                            interference.run();
                        }
                    }
                    default -> {}
                }
                return forward(connection, call, callArgs);
            });
        });
    }

    @FunctionalInterface
    private interface Handler {
        Object handle(Method method, Object[] args) throws Throwable;
    }

    private static <T> T proxy(Class<T> type, Handler handler) {
        return type.cast(Proxy.newProxyInstance(
                JdbcLedgerTest.class.getClassLoader(), new Class<?>[] {type}, (self, method, args) -> handler.handle(method, args)));
    }

    private static Object forward(Object target, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private static Sku sku(String value) {
        return Sku.of(value);
    }

    private static IdempotencyKey key(String value) {
        return IdempotencyKey.of(value);
    }

    private static ReservationId rid(String value) {
        return ReservationId.of(value);
    }
}
