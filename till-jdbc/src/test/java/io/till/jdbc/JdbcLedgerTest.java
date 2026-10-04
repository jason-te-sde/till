package io.till.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.till.core.Codec;
import io.till.core.Command;
import io.till.core.Decision;
import io.till.core.Event;
import io.till.core.IdempotencyKey;
import io.till.core.Kernel;
import io.till.core.Line;
import io.till.core.Mutation;
import io.till.core.Outcome;
import io.till.core.OutcomeRecord;
import io.till.core.OutboxEntry;
import io.till.core.RejectionCode;
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
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
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

    @Test
    @DisplayName("an event leaves for the broker only once the commit that wrote it is on disk")
    void anEventLeavesOnlyOnceItIsDurable() throws Exception {
        // An event committed the way a hold is (ADR 15): with synchronous_commit off, so that the
        // commit returns once its WAL is in the server's buffers, before anything is flushed. Every
        // connection can read it from that moment, the publisher's included. A commit can wake the WAL
        // writer from hibernating, which then flushes it at once, so the test writes events until it
        // has one that is visible and not yet flushed.
        slowWalWriter();
        try {
            String written = null;
            for (int attempt = 1; attempt <= 10 && written == null; attempt++) {
                String lsn = commitAsynchronously(rid("r" + attempt));
                if (!flushedTo(lsn)) {
                    written = lsn;
                }
            }
            assertNotNull(written, "each of ten events committed asynchronously was flushed at once, so this server "
                    + "cannot show one that is visible and not yet on disk");

            String last = written;
            List<Boolean> onDiskWhenSent = new ArrayList<>();
            ledger.publishNext(20, T0, batch -> onDiskWhenSent.add(flushedTo(last)));

            assertEquals(List.of(true), onDiskWhenSent, "a batch was handed over while a crash could still take it back");
        } finally {
            execute("alter system reset wal_writer_delay");
            execute("select pg_reload_conf()");
        }
    }

    /**
     * Slows the server's WAL writer to one flush in ten seconds, until the test resets it. At its
     * default of one in 200 ms, it flushes an asynchronous commit by itself often enough that a
     * publisher which takes a while to reach the broker — a cold JVM's first round does — finds the
     * batch on disk whether or not it flushed it, and the test above passes without the flush half the
     * time. {@code ALTER SYSTEM} needs a superuser, which the user Testcontainers and CI connect as is.
     */
    private void slowWalWriter() throws InterruptedException {
        execute("alter system set wal_writer_delay = '10s'");
        execute("select pg_reload_conf()");
        // The reload signals the WAL writer, which wakes and rereads its settings at once; this is
        // only so that it has before the first commit the test makes.
        Thread.sleep(250);
    }

    /**
     * Writes a hold's event the way ADR 15 commits a hold, with synchronous_commit off for its
     * transaction, and returns where the WAL stood just after: at or past the end of that commit.
     */
    private String commitAsynchronously(ReservationId id) {
        Event event = new Event.StockReserved(id, List.of(Line.of("widget", 1)), T0.plus(TTL), T0);
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement();
                    PreparedStatement insert = connection.prepareStatement(
                            "insert into till_outbox (dedupe_key, payload, recorded_at) values (?, ?, ?)")) {
                statement.execute("set local synchronous_commit = off");
                insert.setString(1, event.dedupeKey());
                insert.setString(2, Codec.encodeEvent(event));
                insert.setObject(3, T0.atOffset(ZoneOffset.UTC));
                insert.executeUpdate();
                connection.commit();
            } finally {
                connection.setAutoCommit(true);
            }
            try (Statement statement = connection.createStatement();
                    ResultSet rows = statement.executeQuery("select pg_current_wal_insert_lsn()::text")) {
                rows.next();
                return rows.getString(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Whether the server has flushed its WAL to disk at least as far as {@code lsn}. */
    private boolean flushedTo(String lsn) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement("select pg_current_wal_flush_lsn() >= ?::pg_lsn")) {
            statement.setString(1, lsn);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getBoolean(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
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
    @DisplayName("the lean statement is lookups under one append: no sort, window, aggregate or join")
    void theLeanStatementSortsAndJoinsNothing() throws SQLException {
        till.adjust(key("d1"), sku("widget"), 10);
        List<String> settings = new CopyOnWriteArrayList<>();
        List<String> statements = new CopyOnWriteArrayList<>();
        DataSource observed = interfering(dataSource, settings, statements, null, () -> {});
        new JdbcLedger(observed).load(new Command.Reserve(key("c1"), rid("r1"), List.of(Line.of("widget", 1)), TTL), T0, 0);

        // Which plan: not the one made for these parameters' values but the one a long-lived connection
        // settles on, because PostgreSQL plans a prepared statement without them once it has seen it a
        // few times. What it must not do is spend a sort, a window, a hash or a join on the handful of
        // rows one load returns, as the statement once did (ADR 12, "Later").
        String plan = genericPlan(statements.get(0));

        for (String node : List.of("Sort", "WindowAgg", "Aggregate", "Unique", "Join", "Nested Loop", "Hash", "Merge")) {
            assertFalse(plan.contains(node), "the lean statement's generic plan has a " + node + " in it:\n" + plan);
        }
    }

    @Test
    @DisplayName("run often enough on one connection, a lean load is planned once and not again on every call")
    void aLeanLoadSettlesOnOnePlan() throws SQLException {
        till.adjust(key("d1"), sku("widget"), 10);
        till.reserve(key("c1"), rid("r1"), List.of(Line.of("widget", 2)), TTL);

        // PostgreSQL plans a prepared statement for its parameters' values the first five times and
        // afterwards keeps the plan that ignores them, unless planning for the values was cheaper by more
        // than planning costs. A statement it never settles on is planned on every call, and planning one
        // of this size costs more than running it. Which side a statement lands on turns on how it is
        // written, not on how much data there is: a lateral join over the command's SKUs reads the stock
        // faster, and is planned on every call, so that it runs faster and costs more.
        try (Connection connection =
                DriverManager.getConnection(TestDatabase.url(), TestDatabase.username(), TestDatabase.password())) {
            JdbcLedger onOneConnection = new JdbcLedger(alwaysTheOne(connection));
            for (int i = 0; i < 20; i++) {
                onOneConnection.load(new Command.Commit(key("pay-" + i), rid("r1")), T0, 0);
            }

            try (Statement statement = connection.createStatement();
                    ResultSet plans =
                            statement.executeQuery(
                                    "select generic_plans, custom_plans from pg_prepared_statements "
                                            + "where statement like '%till_stock%'")) {
                assertTrue(plans.next(), "the driver should have prepared the statement on the server by now");
                long generic = plans.getLong("generic_plans");
                long custom = plans.getLong("custom_plans");
                assertFalse(plans.next(), "one statement is what a lean load prepares");
                assertTrue(
                        generic > custom,
                        "after twenty loads PostgreSQL had planned the statement afresh " + custom + " times and run a generic plan "
                                + generic + " times");
            }
        }
    }

    @Test
    @DisplayName("a lean snapshot is the snapshot a reclaiming load reads, whatever the command names")
    void aLeanSnapshotIsTheTransactionalOne() {
        populateEveryShape();

        for (Command command : everyShapeOfCommand()) {
            Snapshot transactional = ledger.load(command, T0, 32);
            Snapshot lean = ledger.load(command, T0, 0);

            assertEquals(transactional, lean, "a lean and a transactional load disagree for " + command);
            assertEquals(
                    List.copyOf(transactional.stock().keySet()),
                    List.copyOf(lean.stock().keySet()),
                    "the SKUs come in a different order for " + command);
        }

        // Hand-derived, not read back from either path: the first command names every SKU of a hold
        // that is already there, so each is named twice and must still come back once, with every one
        // of its shards. Delta sorts first because upper case does, and the ghost has no row at all.
        Snapshot named =
                ledger.load(
                        new Command.Reserve(
                                key("hold-1"),
                                rid("r1"),
                                List.of(Line.of("alpha", 1), Line.of("beta", 7), Line.of("gamma", 1), Line.of("Delta", 2)),
                                TTL),
                        T0,
                        0);
        assertEquals(List.of(sku("Delta"), sku("alpha"), sku("beta"), sku("gamma")), List.copyOf(named.stock().keySet()));
        assertEquals(List.of(1, 1, 4, 3), named.stock().values().stream().map(List::size).toList());
        assertEquals(
                List.of(Line.of("Delta", 2), Line.of("alpha", 1), Line.of("beta", 7), Line.of("gamma", 1)),
                named.reservation().orElseThrow().lines());
        assertTrue(
                named.reservation().orElseThrow().allocations().stream().filter(a -> a.sku().equals(sku("beta"))).count() > 1,
                "the data was meant to hold beta across more than one shard: " + named.reservation());
        assertTrue(named.recordedOutcome().isPresent());

        Snapshot ghost =
                ledger.load(new Command.Reserve(key("fresh"), rid("r2"), List.of(Line.of("alpha", 1), Line.of("ghost", 1)), TTL), T0, 0);
        assertEquals(List.of(sku("alpha"), sku("ghost")), List.copyOf(ghost.stock().keySet()));
        assertEquals(List.of(), ghost.shards(sku("ghost")));
        assertEquals(Optional.empty(), ghost.reservation());
        assertEquals(Optional.empty(), ghost.recordedOutcome());
    }

    @Test
    @DisplayName("a lean snapshot does not depend on the order the database returns its rows in")
    void aLeanSnapshotDoesNotDependOnRowOrder() {
        populateEveryShape();

        Map<String, UnaryOperator<List<Object[]>>> orders = new LinkedHashMap<>();
        orders.put("reversed", rows -> reorder(rows, copy -> Collections.reverse(copy)));
        orders.put("rotated by one", rows -> reorder(rows, copy -> Collections.rotate(copy, 1)));
        orders.put("rotated by seven", rows -> reorder(rows, copy -> Collections.rotate(copy, 7)));
        for (long seed = 1; seed <= 5; seed++) {
            long chosen = seed;
            orders.put("shuffled with seed " + seed, rows -> reorder(rows, copy -> Collections.shuffle(copy, new Random(chosen))));
        }

        for (Command command : everyShapeOfCommand()) {
            Snapshot expected = ledger.load(command, T0, 0);
            for (Map.Entry<String, UnaryOperator<List<Object[]>>> order : orders.entrySet()) {
                Snapshot actual = new JdbcLedger(reordering(dataSource, order.getValue())).load(command, T0, 0);

                assertEquals(expected, actual, "rows " + order.getKey() + " changed the snapshot for " + command);
                assertEquals(
                        List.copyOf(expected.stock().keySet()),
                        List.copyOf(actual.stock().keySet()),
                        "rows " + order.getKey() + " changed the order of the SKUs for " + command);
            }
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

    @Test
    @DisplayName("a reserve, and the commit after it, are each written in one statement, with nothing begun or committed around it")
    void aDecisionIsOneStatement() {
        till.adjust(key("d1"), sku("widget"), 10);
        List<String> calls = new CopyOnWriteArrayList<>();
        List<String> executed = new CopyOnWriteArrayList<>();
        JdbcLedger observed =
                new JdbcLedger(interfering(dataSource, new CopyOnWriteArrayList<>(), calls, executed, null, () -> {}));

        Command reserve = new Command.Reserve(key("c1"), rid("r1"), List.of(Line.of("widget", 2)), TTL);
        assertTrue(observed.apply(Kernel.decide(ledger.load(reserve, T0, 0), reserve, T0)));

        assertEquals(1, executed.size(), "one statement executed, got " + executed);
        assertEquals(executed, calls, "and no transaction begun, committed or rolled back around it");
        // Not merely one statement: the whole decision, written by it.
        assertEquals(2, ledger.stock(sku("widget")).orElseThrow().reserved());
        assertEquals(List.of(rid("r1")), ledger.allReservations().stream().map(Reservation::id).toList());
        assertEquals(List.of(Line.of("widget", 2)), ledger.allReservations().get(0).lines());
        assertEquals(List.of("adjusted:d1", "reserved:r1"), dedupeKeys());
        assertEquals(1, count("select count(*) from till_idempotency where idem_key = 'c1'"));

        executed.clear();
        calls.clear();
        Command commit = new Command.Commit(key("p1"), rid("r1"));
        assertTrue(observed.apply(Kernel.decide(ledger.load(commit, T0, 0), commit, T0)));

        assertEquals(1, executed.size(), "one statement executed, got " + executed);
        assertEquals(executed, calls, "and no transaction begun, committed or rolled back around it");
        assertEquals(8, ledger.stock(sku("widget")).orElseThrow().onHand());
        assertEquals(ReservationState.COMMITTED, ledger.allReservations().get(0).state());
        assertEquals(List.of("adjusted:d1", "reserved:r1", "committed:r1"), dedupeKeys());
        assertEquals(1, count("select count(*) from till_idempotency where idem_key = 'p1'"));
    }

    @Test
    @DisplayName("a decision with one stale row is refused in one statement, and nothing of it is written")
    void aStaleDecisionWritesNothing() {
        till.adjust(key("d1"), sku("gadget"), 10);
        till.adjust(key("d2"), sku("widget"), 10);
        Command command =
                new Command.Reserve(key("k1"), rid("r1"), List.of(Line.of("gadget", 1), Line.of("widget", 1)), TTL);
        Decision stale = Kernel.decide(ledger.load(command, T0, 0), command, T0);
        // The decision writes its stock rows in SKU order, gadget's before widget's, so with widget's
        // moved the refusal comes after gadget's row has been written by the same statement.
        till.adjust(key("d3"), sku("widget"), 5);

        List<String> calls = new CopyOnWriteArrayList<>();
        List<String> executed = new CopyOnWriteArrayList<>();
        JdbcLedger observed =
                new JdbcLedger(interfering(dataSource, new CopyOnWriteArrayList<>(), calls, executed, null, () -> {}));

        boolean applied = observed.apply(stale);

        StockItem gadget = ledger.stock(sku("gadget")).orElseThrow();
        assertEquals(0, gadget.reserved(), "the row written before the refusal must not survive it: " + gadget);
        assertEquals(0, gadget.version(), "not even its version: " + gadget);
        StockItem widget = ledger.stock(sku("widget")).orElseThrow();
        assertEquals(15, widget.onHand(), "the adjustment that moved it stands: " + widget);
        assertEquals(0, widget.reserved(), widget.toString());
        assertEquals(0, count("select count(*) from till_reservation"), "no reservation");
        assertEquals(0, count("select count(*) from till_reservation_line"), "no line");
        assertEquals(List.of("adjusted:d1", "adjusted:d2", "adjusted:d3"), dedupeKeys(), "no event");
        assertEquals(0, count("select count(*) from till_idempotency where idem_key = 'k1'"), "no record");
        assertFalse(applied, "and the caller is told to decide again");
        assertEquals(1, executed.size(), "refused in one statement, got " + executed);
        assertEquals(executed, calls, "and no transaction begun, committed or rolled back around it");
    }

    @Test
    @DisplayName("a check violation in that one statement is an exception, never a refusal")
    void aCheckViolationIsAnException() {
        till.adjust(key("d1"), sku("widget"), 10);
        Command command = new Command.Adjust(key("d2"), sku("widget"), 3);
        Decision decision = Kernel.decide(ledger.load(command, T0, 0), command, T0);
        List<String> calls = new CopyOnWriteArrayList<>();
        List<String> executed = new CopyOnWriteArrayList<>();
        JdbcLedger observed =
                new JdbcLedger(interfering(dataSource, new CopyOnWriteArrayList<>(), calls, executed, null, () -> {}));

        // A rule the kernel knows nothing about, so that a level it computed can break one: the
        // decision above takes the widget to thirteen.
        execute("alter table till_stock add constraint till_test_unlucky check (on_hand <> 13)");
        try {
            LedgerException thrown = assertThrows(LedgerException.class, () -> observed.apply(decision));

            assertEquals("23514", ((SQLException) thrown.getCause()).getSQLState(), thrown.getMessage());
            assertEquals(10, ledger.stock(sku("widget")).orElseThrow().onHand(), "nothing was written");
            assertEquals(List.of("adjusted:d1"), dedupeKeys(), "no event");
            assertEquals(0, count("select count(*) from till_idempotency where idem_key = 'd2'"), "no record");
            assertEquals(1, executed.size(), "one statement, got " + executed);
            assertEquals(executed, calls, "and no transaction begun, committed or rolled back around it");
        } finally {
            execute("alter table till_stock drop constraint till_test_unlucky");
        }
    }

    @Test
    @DisplayName("on a connection handed out without autocommit, a decision is still committed, and the connection put back as it was")
    void aDecisionIsCommittedOffAutocommit() {
        till.adjust(key("d1"), sku("widget"), 10);
        Command reserve = new Command.Reserve(key("c1"), rid("r1"), List.of(Line.of("widget", 1)), TTL);
        Decision decision = Kernel.decide(ledger.load(reserve, T0, 0), reserve, T0);
        // A pool configured with autoCommit=false hands out connections like these.
        DataSource manual = proxy(DataSource.class, (method, args) -> {
            Object result = forward(dataSource, method, args);
            if (method.getName().equals("getConnection")) {
                ((Connection) result).setAutoCommit(false);
            }
            return result;
        });
        List<String> calls = new CopyOnWriteArrayList<>();
        JdbcLedger observed = new JdbcLedger(
                interfering(manual, new CopyOnWriteArrayList<>(), calls, new CopyOnWriteArrayList<>(), null, () -> {}));

        assertTrue(observed.apply(decision));

        // Read on other connections: committed, not left in a transaction the pool rolls back.
        assertEquals(List.of(rid("r1")), ledger.allReservations().stream().map(Reservation::id).toList());
        assertEquals(1, ledger.stock(sku("widget")).orElseThrow().reserved());
        assertEquals("setAutoCommit(true)", calls.get(0), "switched for the statement: " + calls);
        assertEquals("setAutoCommit(false)", calls.get(calls.size() - 1), "and back afterwards: " + calls);
    }

    // Each kind of row the function inserts or moves is refused the same way when somebody got there
    // first: false, and nothing of the decision written — including the stock row it had already
    // written in the same statement. The stale stock update is aStaleDecisionWritesNothing above.

    @Test
    @DisplayName("a stock row somebody else created first is a refusal, and the row written before it is undone")
    void aStockRowAlreadyThereIsARefusal() {
        till.adjust(key("d1"), sku("widget"), 10);
        Command split = new Command.Shard(key("s1"), sku("widget"), 2);
        Decision decision = Kernel.decide(ledger.load(split, T0, 0), split, T0);
        // Another split got there first, with the second shard.
        execute("insert into till_stock (sku, shard, on_hand, reserved, version) values ('widget', 1, 0, 0, 0)");

        assertFalse(ledger.apply(decision));

        assertEquals(
                List.of("widget#0 10/0 v0", "widget#1 0/0 v0"),
                ledger.allShards().stream()
                        .map(s -> s.sku() + "#" + s.index() + " " + s.onHand() + "/" + s.reserved() + " v" + s.version())
                        .toList(),
                "shard 0, written before the refusal, is as it was");
        assertEquals(0, count("select count(*) from till_idempotency where idem_key = 's1'"), "no record");
    }

    @Test
    @DisplayName("a reservation id taken first is a refusal, and the stock row written before it is undone")
    void aReservationIdTakenIsARefusal() {
        till.adjust(key("d1"), sku("widget"), 10);
        till.adjust(key("d2"), sku("gadget"), 10);
        Command reserve = new Command.Reserve(key("k1"), rid("r1"), List.of(Line.of("widget", 1)), TTL);
        Decision decision = Kernel.decide(ledger.load(reserve, T0, 0), reserve, T0);
        till.reserve(key("k2"), rid("r1"), List.of(Line.of("gadget", 1)), TTL);

        assertFalse(ledger.apply(decision));

        StockItem widget = ledger.stock(sku("widget")).orElseThrow();
        assertEquals(0, widget.reserved(), "written before the refusal, and undone: " + widget);
        assertEquals(0, widget.version(), widget.toString());
        assertEquals(List.of(Line.of("gadget", 1)), ledger.allReservations().get(0).lines(), "r1 is still the other one");
        assertEquals(List.of("adjusted:d1", "adjusted:d2", "reserved:r1"), dedupeKeys(), "and so is its event");
        assertEquals(0, count("select count(*) from till_idempotency where idem_key = 'k1'"), "no record");
    }

    @Test
    @DisplayName("a reservation not at its expected version is a refusal, and the stock row written before it is undone")
    void aReservationThatMovedIsARefusal() {
        till.adjust(key("d1"), sku("widget"), 10);
        till.reserve(key("k1"), rid("r1"), List.of(Line.of("widget", 1)), TTL);
        Command commit = new Command.Commit(key("p1"), rid("r1"));
        Decision decision = Kernel.decide(ledger.load(commit, T0, 0), commit, T0);
        execute("update till_reservation set version = version + 1 where id = 'r1'");

        assertFalse(ledger.apply(decision));

        StockItem widget = ledger.stock(sku("widget")).orElseThrow();
        assertEquals(10, widget.onHand(), "written before the refusal, and undone: " + widget);
        assertEquals(1, widget.reserved(), widget.toString());
        assertEquals(ReservationState.HELD, ledger.allReservations().get(0).state());
        assertEquals(List.of("adjusted:d1", "reserved:r1"), dedupeKeys(), "no event");
        assertEquals(0, count("select count(*) from till_idempotency where idem_key = 'p1'"), "no record");
    }

    @Test
    @DisplayName("an event already in the outbox is a refusal, and the stock row written before it is undone")
    void anEventAlreadyWrittenIsARefusal() {
        till.adjust(key("d1"), sku("widget"), 10);
        Command adjust = new Command.Adjust(key("d2"), sku("widget"), 5);
        Decision decision = Kernel.decide(ledger.load(adjust, T0, 0), adjust, T0);
        // What an earlier execution leaves behind when its record is pruned and its event is not, the
        // case FORGET_IDEMPOTENCY guards against: the same event, already in the outbox.
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "insert into till_outbox (dedupe_key, payload, recorded_at) values ('adjusted:d2', ?, now())")) {
            statement.setString(1, Codec.encodeEvent(decision.events().get(0)));
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        long sequence = count("select sequence from till_outbox where dedupe_key = 'adjusted:d2'");

        assertFalse(ledger.apply(decision));

        assertEquals(10, ledger.stock(sku("widget")).orElseThrow().onHand(), "written before the refusal, and undone");
        assertEquals(List.of("adjusted:d1", "adjusted:d2"), dedupeKeys());
        assertEquals(
                sequence, count("select sequence from till_outbox where dedupe_key = 'adjusted:d2'"), "the old row stands");
        assertEquals(0, count("select count(*) from till_idempotency where idem_key = 'd2'"), "no record");
    }

    @Test
    @DisplayName("an idempotency key claimed first is a refusal, and everything written before it is undone")
    void anIdempotencyKeyClaimedIsARefusal() {
        till.adjust(key("d1"), sku("widget"), 10);
        Command reserve = new Command.Reserve(key("k1"), rid("r1"), List.of(Line.of("widget", 1)), TTL);
        Decision decision = Kernel.decide(ledger.load(reserve, T0, 0), reserve, T0);
        // A different request under the same key, answered and recorded first: a refusal of its own,
        // which writes nothing but the record.
        till.commit(key("k1"), rid("ghost"));

        assertFalse(ledger.apply(decision));

        StockItem widget = ledger.stock(sku("widget")).orElseThrow();
        assertEquals(0, widget.reserved(), "the stock row, written first, is undone: " + widget);
        assertEquals(0, widget.version(), widget.toString());
        assertEquals(0, count("select count(*) from till_reservation"), "and the reservation");
        assertEquals(0, count("select count(*) from till_reservation_line"), "and its line");
        assertEquals(List.of("adjusted:d1"), dedupeKeys(), "and its event");
        assertEquals(1, count("select count(*) from till_idempotency where idem_key = 'k1'"), "the first record stands");
    }

    @Test
    @DisplayName("arrays of one kind that disagree in length are a malformed call, not a refusal")
    void aMalformedCallIsNotARefusal() throws SQLException {
        till.adjust(key("d1"), sku("widget"), 10);

        // One stock row with no shard: a missing element would read as null, match no row, and look
        // exactly like a version that moved, retried until the caller gave up on contention.
        try (Connection connection = dataSource.getConnection();
                PreparedStatement call = connection.prepareStatement(
                        "select till_apply("
                                + "put_sku => array['widget'], put_shard => array[]::integer[], "
                                + "put_on_hand => array[10::bigint], put_reserved => array[1::bigint], "
                                + "put_version => array[0::bigint], "
                                + "set_id => '{}', set_state => '{}', set_version => '{}', "
                                + "insert_id => '{}', insert_key => '{}', insert_state => '{}', "
                                + "insert_created_at => '{}', insert_expires_at => '{}', "
                                + "line_reservation => '{}', line_sku => '{}', line_shard => '{}', "
                                + "line_quantity => '{}', event_key => '{}', event_payload => '{}', "
                                + "event_recorded_at => '{}', record_key => null, record_fingerprint => null, "
                                + "record_outcome => null, record_recorded_at => null)")) {
            SQLException thrown = assertThrows(SQLException.class, call::execute);

            assertEquals("22023", thrown.getSQLState(), thrown.getMessage());
        }
        assertEquals(0, ledger.stock(sku("widget")).orElseThrow().reserved());
    }

    @Test
    @DisplayName("till_apply told a decision need not be durable commits it without waiting for the WAL, in that transaction only")
    void aDecisionThatNeedNotBeDurableTurnsSynchronousCommitOff() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                assertEquals("off", commitModeAfter(connection, applyNothing(", durable => false")));
                connection.rollback();
                assertEquals("on", commitModeAfter(connection, null), "the next transaction is back to the default");

                assertEquals("on", commitModeAfter(connection, applyNothing(", durable => true")));
                connection.rollback();
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    @Test
    @DisplayName("an instance still sending the last version's call, which does not name durable, is answered, and durably")
    void theLastVersionsCallIsStillAnswered() throws SQLException {
        // A rolling deploy migrates the database when its first new instance starts, while instances
        // of the last version are still serving; they send V4's call, typed as they bind it, with
        // nothing to write here but a record.
        String[] arrays = {
            "varchar", "integer", "bigint", "bigint", "bigint", "varchar", "varchar", "bigint", "varchar", "varchar",
            "varchar", "timestamptz", "timestamptz", "varchar", "varchar", "integer", "bigint", "varchar", "text",
            "timestamptz"
        };
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement call = connection.prepareStatement(LAST_VERSIONS_APPLY)) {
                for (int i = 0; i < arrays.length; i++) {
                    call.setArray(i + 1, connection.createArrayOf(arrays[i], new Object[0]));
                }
                call.setString(21, "from-v4");
                call.setString(22, "fingerprint");
                call.setString(23, "outcome");
                call.setObject(24, T0.atOffset(ZoneOffset.UTC), Types.TIMESTAMP_WITH_TIMEZONE);
                call.execute();

                assertEquals("on", commitModeAfter(connection, null), "it commits as it always did");
                connection.commit();
            } finally {
                connection.setAutoCommit(true);
            }
        }
        assertEquals(1, count("select count(*) from till_idempotency where idem_key = 'from-v4'"));
    }

    @Test
    @DisplayName("a hold taken, given back or written off, and a refusal, commit without waiting for the WAL; every other outcome waits")
    void onlyHoldsAndRefusalsCommitWithoutWaiting() {
        // Every kind of outcome, and synchronous_commit as it must stand when its decision is written.
        // A kind added to Outcome and not here fails below, so that somebody decides which it is.
        Map<Class<?>, Durability> table = new LinkedHashMap<>();
        table.put(Outcome.Reserved.class,
                new Durability(new Outcome.Reserved(rid("r1"), List.of(Line.of("widget", 1)), T0.plus(TTL)), "off"));
        table.put(Outcome.Released.class, new Durability(new Outcome.Released(rid("r1"), T0), "off"));
        table.put(Outcome.Swept.class, new Durability(new Outcome.Swept(3), "off"));
        table.put(Outcome.Rejected.class,
                new Durability(Outcome.Rejected.of(RejectionCode.RESERVATION_EXPIRED, "r1 expired"), "off"));
        table.put(Outcome.Committed.class, new Durability(new Outcome.Committed(rid("r1"), T0), "on"));
        table.put(Outcome.Adjusted.class, new Durability(new Outcome.Adjusted(sku("widget"), 10, 0), "on"));
        table.put(Outcome.Sharded.class, new Durability(new Outcome.Sharded(sku("widget"), 4), "on"));
        assertEquals(
                Set.of(Outcome.class.getPermittedSubclasses()),
                table.keySet(),
                "every kind of outcome is classified here: a hold, which may commit without waiting for its WAL, "
                        + "or something that moves stock for good, which may not");

        // What the database saw: the setting, read inside the statement that writes the decision's record.
        execute("create table till_test_commit_mode (idem_key varchar primary key, synchronous_commit text)");
        execute("create function till_test_commit_mode() returns trigger language plpgsql as $$ begin "
                + "insert into till_test_commit_mode values (new.idem_key, current_setting('synchronous_commit')); "
                + "return null; end $$");
        execute("create trigger till_test_commit_mode after insert on till_idempotency "
                + "for each row execute function till_test_commit_mode()");
        try {
            Map<String, String> expected = new LinkedHashMap<>();
            Map<String, String> seen = new LinkedHashMap<>();
            table.forEach((kind, durability) -> {
                // Made up: an outcome and a record of it, which is all apply reads to tell them apart,
                // and the one row the trigger watches.
                String key = "mode-" + kind.getSimpleName();
                Outcome outcome = durability.sample();
                OutcomeRecord record = new OutcomeRecord(key(key), "fingerprint", Codec.encodeOutcome(outcome), T0);
                assertTrue(ledger.apply(new Decision(outcome, List.of(), List.of(), Optional.of(record))));
                expected.put(kind.getSimpleName(), durability.synchronousCommit());
                seen.put(kind.getSimpleName(), text("select synchronous_commit from till_test_commit_mode where idem_key = '"
                        + key + "'"));
            });

            assertEquals(expected, seen);
        } finally {
            execute("drop trigger till_test_commit_mode on till_idempotency");
            execute("drop function till_test_commit_mode()");
            execute("drop table till_test_commit_mode");
        }
    }

    /** An outcome of one kind, and what synchronous_commit must be while its decision is written. */
    private record Durability(Outcome sample, String synchronousCommit) {}

    /** The statement {@code JdbcLedger} sent before V5: every argument named, none of them durable. */
    private static final String LAST_VERSIONS_APPLY =
            "select till_apply("
                    + "put_sku => ?, put_shard => ?, put_on_hand => ?, put_reserved => ?, put_version => ?, "
                    + "set_id => ?, set_state => ?, set_version => ?, "
                    + "insert_id => ?, insert_key => ?, insert_state => ?, insert_created_at => ?, "
                    + "insert_expires_at => ?, "
                    + "line_reservation => ?, line_sku => ?, line_shard => ?, line_quantity => ?, "
                    + "event_key => ?, event_payload => ?, event_recorded_at => ?, "
                    + "record_key => ?, record_fingerprint => ?, record_outcome => ?, record_recorded_at => ?)";

    /**
     * Runs {@code sql}, if any, in the connection's current transaction, and answers {@code show
     * synchronous_commit} from inside the same transaction.
     */
    private static String commitModeAfter(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            if (sql != null) {
                statement.execute(sql);
            }
            try (ResultSet rows = statement.executeQuery("show synchronous_commit")) {
                rows.next();
                return rows.getString(1);
            }
        }
    }

    /** A call to {@code till_apply} with nothing to write — every array empty, no record — and {@code more}. */
    private static String applyNothing(String more) {
        return "select till_apply("
                + "put_sku => '{}', put_shard => '{}', put_on_hand => '{}', put_reserved => '{}', put_version => '{}', "
                + "set_id => '{}', set_state => '{}', set_version => '{}', "
                + "insert_id => '{}', insert_key => '{}', insert_state => '{}', insert_created_at => '{}', "
                + "insert_expires_at => '{}', "
                + "line_reservation => '{}', line_sku => '{}', line_shard => '{}', line_quantity => '{}', "
                + "event_key => '{}', event_payload => '{}', event_recorded_at => '{}', "
                + "record_key => null, record_fingerprint => null, record_outcome => null, record_recorded_at => null"
                + more + ")";
    }

    @Test
    @DisplayName("a decision listing its rows in an order the function cannot keep is refused, and nothing is sent")
    void aDecisionOutOfOrderIsRefused() {
        till.adjust(key("d1"), sku("widget"), 10);
        till.reserve(key("c1"), rid("r1"), List.of(Line.of("widget", 1)), TTL);
        long version = ledger.allReservations().get(0).version();
        // The kernel lists stock rows, then state changes, then new reservations; the function writes
        // them in that order. This lists a new reservation first.
        Decision outOfOrder =
                new Decision(
                        new Outcome.Released(rid("r1"), T0),
                        List.of(
                                new Mutation.InsertReservation(
                                        new Reservation(
                                                rid("r2"), key("c2"), List.of(Line.of("widget", 1)),
                                                ReservationState.HELD, T0, T0.plus(TTL), 0)),
                                new Mutation.SetReservationState(rid("r1"), ReservationState.RELEASED, version)),
                        List.of(),
                        Optional.empty());
        List<String> executed = new CopyOnWriteArrayList<>();
        JdbcLedger observed = new JdbcLedger(
                interfering(dataSource, new CopyOnWriteArrayList<>(), new CopyOnWriteArrayList<>(), executed, null, () -> {}));

        assertThrows(IllegalArgumentException.class, () -> observed.apply(outOfOrder));

        assertEquals(List.of(), executed, "nothing was sent");
        assertEquals(List.of(rid("r1")), ledger.allReservations().stream().map(Reservation::id).toList());
        assertEquals(ReservationState.HELD, ledger.allReservations().get(0).state());
    }

    private List<String> dedupeKeys() {
        return ledger.allEvents().stream().map(OutboxEntry::dedupeKey).toList();
    }

    private long count(String sql) {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getLong(1);
        } catch (SQLException e) {
            throw new IllegalStateException(sql, e);
        }
    }

    /** The first column of the first row, or null if there is none. */
    private String text(String sql) {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            return rows.next() ? rows.getString(1) : null;
        } catch (SQLException e) {
            throw new IllegalStateException(sql, e);
        }
    }

    private void execute(String sql) {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException(sql, e);
        }
    }

    /**
     * A ledger with every shape a snapshot has to describe: a SKU in one row, SKUs in four and in three,
     * a SKU whose name sorts before the others only in code point order, and one hold across all four,
     * which takes the larger one's units from two of its shards.
     */
    private void populateEveryShape() {
        till.adjust(key("d-alpha"), sku("alpha"), 10);
        till.adjust(key("d-beta"), sku("beta"), 20);
        till.shard(key("s-beta"), sku("beta"), 4);
        till.adjust(key("d-gamma"), sku("gamma"), 9);
        till.shard(key("s-gamma"), sku("gamma"), 3);
        till.adjust(key("d-delta"), sku("Delta"), 4);
        Outcome held =
                till.reserve(
                        key("hold-1"),
                        rid("r1"),
                        List.of(Line.of("alpha", 1), Line.of("beta", 7), Line.of("gamma", 1), Line.of("Delta", 2)),
                        TTL);
        assertInstanceOf(Outcome.Reserved.class, held);
    }

    /**
     * One command for each way a load's inputs can be present or absent: a key that was used and one
     * that was not, a hold that exists and one that does not, SKUs the ledger has, the ones the hold
     * names too, and one it has never heard of, and the commands that name no hold or no SKU at all.
     */
    private static List<Command> everyShapeOfCommand() {
        List<Line> held = List.of(Line.of("alpha", 1), Line.of("beta", 7), Line.of("gamma", 1), Line.of("Delta", 2));
        return List.of(
                new Command.Reserve(key("hold-1"), rid("r1"), held, TTL),
                new Command.Reserve(key("fresh"), rid("r2"), List.of(Line.of("alpha", 1), Line.of("ghost", 1)), TTL),
                new Command.Reserve(key("fresh"), rid("r1"), List.of(Line.of("beta", 1)), TTL),
                new Command.Commit(key("pay-1"), rid("r1")),
                new Command.Commit(key("pay-2"), rid("nowhere")),
                new Command.Release(key("hold-1"), rid("r1")),
                new Command.Adjust(key("d-beta"), sku("beta"), 5),
                new Command.Adjust(key("d-new"), sku("ghost"), 5),
                new Command.Shard(key("s-new"), sku("alpha"), 2),
                new Command.Sweep(10));
    }

    /** The plan PostgreSQL makes for {@code sql} without knowing the value of any parameter. */
    private static String genericPlan(String sql) throws SQLException {
        StringBuilder numbered = new StringBuilder();
        int parameters = 0;
        for (char c : sql.toCharArray()) {
            if (c == '?') {
                numbered.append('$').append(++parameters);
            } else {
                numbered.append(c);
            }
        }
        // A connection of its own: both settings belong to the connection, and the pool's are shared.
        try (Connection connection =
                        DriverManager.getConnection(TestDatabase.url(), TestDatabase.username(), TestDatabase.password());
                Statement statement = connection.createStatement()) {
            statement.execute("set plan_cache_mode = force_generic_plan");
            statement.execute("prepare lean_plan as " + numbered);
            String nulls = String.join(", ", Collections.nCopies(parameters, "null"));
            StringBuilder plan = new StringBuilder();
            try (ResultSet rows = statement.executeQuery("explain (costs off) execute lean_plan(" + nulls + ")")) {
                while (rows.next()) {
                    plan.append(rows.getString(1)).append('\n');
                }
            }
            return plan.toString();
        }
    }

    /** A pool of the one connection, which a caller's {@code close} gives back without closing it. */
    private static DataSource alwaysTheOne(Connection only) {
        return proxy(DataSource.class, (method, args) -> {
            if (!method.getName().equals("getConnection")) {
                throw new UnsupportedOperationException(method.getName());
            }
            return proxy(Connection.class, (call, callArgs) -> call.getName().equals("close") ? null : forward(only, call, callArgs));
        });
    }

    private static List<Object[]> reorder(List<Object[]> rows, Consumer<List<Object[]>> order) {
        List<Object[]> copy = new ArrayList<>(rows);
        order.accept(copy);
        return copy;
    }

    /**
     * A pool whose queries come back with their rows in an order {@code order} chooses, not the one
     * the database did: each result is read whole, put through {@code order}, and handed back a row at
     * a time. Only the getters a load calls are there; another one is an error, so a load that starts
     * to read something else fails here rather than quietly reading nothing.
     */
    private static DataSource reordering(DataSource real, UnaryOperator<List<Object[]>> order) {
        return proxy(DataSource.class, (method, args) -> {
            Object result = forward(real, method, args);
            if (!method.getName().equals("getConnection")) {
                return result;
            }
            Connection connection = (Connection) result;
            return proxy(Connection.class, (call, callArgs) -> {
                Object prepared = forward(connection, call, callArgs);
                if (!call.getName().equals("prepareStatement")) {
                    return prepared;
                }
                PreparedStatement statement = (PreparedStatement) prepared;
                return proxy(PreparedStatement.class, (statementCall, statementArgs) -> {
                    if (!statementCall.getName().equals("executeQuery")) {
                        return forward(statement, statementCall, statementArgs);
                    }
                    try (ResultSet rows = statement.executeQuery()) {
                        return replay(rows, order);
                    }
                });
            });
        });
    }

    private static ResultSet replay(ResultSet rows, UnaryOperator<List<Object[]>> order) throws SQLException {
        ResultSetMetaData metadata = rows.getMetaData();
        Map<String, Integer> columns = new HashMap<>();
        for (int i = 1; i <= metadata.getColumnCount(); i++) {
            columns.put(metadata.getColumnLabel(i).toLowerCase(Locale.ROOT), i - 1);
        }
        List<Object[]> buffered = new ArrayList<>();
        while (rows.next()) {
            Object[] row = new Object[columns.size()];
            for (int i = 0; i < row.length; i++) {
                row[i] = rows.getObject(i + 1);
            }
            buffered.add(row);
        }
        Iterator<Object[]> remaining = order.apply(buffered).iterator();
        AtomicReference<Object> last = new AtomicReference<>();
        AtomicReference<Object[]> current = new AtomicReference<>();
        return proxy(ResultSet.class, (call, args) -> {
            switch (call.getName()) {
                case "next" -> {
                    current.set(remaining.hasNext() ? remaining.next() : null);
                    return current.get() != null;
                }
                case "close" -> {
                    return null;
                }
                case "wasNull" -> {
                    return last.get() == null;
                }
                case "getString", "getInt", "getLong", "getObject" -> {
                    if (!(args[0] instanceof String label)) {
                        throw new UnsupportedOperationException("a load read " + call + " by position, which a replay does not do");
                    }
                    Object value = current.get()[columns.get(label.toLowerCase(Locale.ROOT))];
                    last.set(value);
                    return switch (call.getName()) {
                        case "getString" -> value == null ? null : value.toString();
                        case "getInt" -> value == null ? 0 : ((Number) value).intValue();
                        case "getLong" -> value == null ? 0L : ((Number) value).longValue();
                        default -> value == null ? null : asOffsetDateTime(value);
                    };
                }
                default -> throw new UnsupportedOperationException("a load read " + call + ", which a replay does not do");
            }
        });
    }

    private static OffsetDateTime asOffsetDateTime(Object value) {
        return value instanceof Timestamp timestamp
                ? timestamp.toInstant().atOffset(ZoneOffset.UTC)
                : (OffsetDateTime) value;
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
        return interfering(real, settings, statements, new CopyOnWriteArrayList<>(), before, interference);
    }

    /**
     * As above, and also records into {@code executed} the text of every statement the server is
     * sent, once per execution: a statement prepared once and executed for each row of a batch is
     * there once per row, and a plain {@code createStatement} one is there too. With
     * {@code statements}, that is what went over the wire: {@code executed} the statements, and
     * {@code statements} whether anything began, committed or rolled back a transaction around them.
     */
    private static DataSource interfering(
            DataSource real,
            List<String> settings,
            List<String> statements,
            List<String> executed,
            String before,
            Interference interference) {
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
                Object made = forward(connection, call, callArgs);
                return switch (call.getName()) {
                    case "prepareStatement" ->
                            executions(PreparedStatement.class, (PreparedStatement) made, (String) callArgs[0], executed);
                    case "createStatement" -> executions(Statement.class, (Statement) made, null, executed);
                    default -> made;
                };
            });
        });
    }

    /** A statement that adds each of its executions, and each row of a batch, to {@code executed}. */
    private static <S extends Statement> S executions(Class<S> type, S statement, String prepared, List<String> executed) {
        List<String> batch = new ArrayList<>();
        return proxy(type, (method, args) -> {
            String sql = args != null && args.length > 0 && args[0] instanceof String text ? text : prepared;
            switch (method.getName()) {
                case "addBatch" -> batch.add(sql);
                case "clearBatch" -> batch.clear();
                case "executeBatch", "executeLargeBatch" -> {
                    executed.addAll(batch);
                    batch.clear();
                }
                case "execute", "executeQuery", "executeUpdate", "executeLargeUpdate" -> executed.add(sql);
                default -> {}
            }
            return forward(statement, method, args);
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
