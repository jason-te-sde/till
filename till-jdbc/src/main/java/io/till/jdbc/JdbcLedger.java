package io.till.jdbc;

import io.till.core.Allocation;
import io.till.core.BatchDecision;
import io.till.core.BatchSnapshot;
import io.till.core.Codec;
import io.till.core.Command;
import io.till.core.Decision;
import io.till.core.Event;
import io.till.core.IdempotencyKey;
import io.till.core.Ledger;
import io.till.core.LedgerInspector;
import io.till.core.Line;
import io.till.core.Mutation;
import io.till.core.Outbox;
import io.till.core.OutboxEntry;
import io.till.core.Outcome;
import io.till.core.OutcomeRecord;
import io.till.core.Reservation;
import io.till.core.Retention;
import io.till.core.ReservationId;
import io.till.core.ReservationState;
import io.till.core.Sku;
import io.till.core.Snapshot;
import io.till.core.StockItem;
import io.till.core.StockShard;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import javax.sql.DataSource;

/**
 * The {@link Ledger} on PostgreSQL.
 *
 * <p>The kernel's contract is that a snapshot is one instant. Reading gets there two ways:
 *
 * <ul>
 *   <li><b>A load that reclaims nothing</b> ({@code reclaimLimit == 0}: every reserve, commit and
 *       release's first try, by far the common case) is one statement, in autocommit, with no
 *       transaction of its own — see {@link #loadLean}. PostgreSQL takes one snapshot per statement,
 *       so a single statement already reads at one instant; it needs no transaction to say so.
 *   <li><b>A load that reclaims, and the sweep,</b> still open a read-only repeatable-read
 *       transaction: what they read next depends on what the first read found, so it cannot be
 *       folded into one statement the way the lean statement folds a reservation's lines into its
 *       stock read. At read committed, the several statements that path takes would each see a
 *       different instant — each row correct, the set of them describing a state that never
 *       existed.
 * </ul>
 *
 * <p><b>Writing</b> is one statement, in autocommit, at read committed (see {@link #apply}): the
 * function {@code till_apply} takes the whole decision and writes it, every row checked against the
 * version the decision expects. Nothing is locked between loading and writing, so a caller that
 * thinks for a second blocks nobody, and nothing is held locked across a round trip either, because
 * the transaction begins and ends inside the one statement. If a row moved underneath the decision,
 * its update matches no row, the function raises, everything the statement wrote is undone, and
 * {@code apply} returns {@code false} so that the caller can decide again against what is there now.
 * A hold taken, given back or written off, and a refusal, commit without waiting for their WAL to be
 * flushed; everything else waits for it ({@link #ASYNCHRONOUS}, ADR 15).
 *
 * <p><b>Refused is not failed.</b> A version that has moved, a taken reservation id, an idempotency
 * key claimed by a concurrent copy of the same request: all of them return {@code false}, which is
 * the ordinary outcome of contention. A check constraint violation is <i>not</i> treated that way and
 * raises {@link LedgerException} instead, because that means the application tried to write a level
 * the database knows is impossible, and retrying it would loop forever around a real bug.
 *
 * <p>A taken key is found with {@code on conflict do nothing} and a row count rather than by
 * catching a unique violation, and only the function's own SQLState is read as a refusal. A unique
 * violation is the database's error, raised where no conflict was expected — a reservation's line
 * written twice — and stays an exception like any other.
 *
 * <p>Instances hold nothing but the {@link DataSource} and are safe to share between threads.
 *
 * <p>Requires the schema in {@code db/migration}, every file of it; see {@link JdbcSchema}.
 */
public final class JdbcLedger implements Ledger, LedgerInspector, Outbox, Retention {

    /** A snapshot's transaction: one instant, and nothing written from it. */
    private static final String SNAPSHOT_TRANSACTION = "set transaction isolation level repeatable read, read only";

    private static final String SELECT_RECORD =
            "select idem_key, fingerprint, outcome, recorded_at from till_idempotency where idem_key = ?";

    private static final String SELECT_RESERVATION =
            "select id, idem_key, state, created_at, expires_at, version from till_reservation where id = ?";

    /** A hold's lines: one row per shard of a SKU it took units from. */
    private static final String SELECT_LINES =
            "select reservation_id, sku, shard, quantity from till_reservation_line "
                    + "where reservation_id = any(?) order by reservation_id, sku, shard";

    /** Every shard of each SKU in scope, because a refusal has to see the whole SKU (ADR 9). */
    private static final String SELECT_STOCK =
            "select sku, shard, on_hand, reserved, version from till_stock where sku = any(?) "
                    + "order by sku collate \"C\", shard";

    /**
     * Everything a lean load ({@code reclaimLimit == 0}: see {@link #loadLean}) reads, in one
     * statement: the idempotency record, the named reservation and its lines, and the stock of every
     * SKU in scope, including the SKUs the reservation's lines add. {@code UNION ALL} over four typed
     * arms, a {@code kind} column saying which, and the other columns reused across arms rather than
     * named per arm — {@link #assembleLeanSnapshot} has the layout — because PostgreSQL requires one
     * statement's arms to agree on column count and type, and four genuinely different row shapes
     * otherwise mean a wide, mostly-{@code null} row either way.
     *
     * <p>The parameters, in the order they appear: the target reservation (for {@code target_lines}),
     * the idempotency key, the target reservation again (for its header), and the command's own SKUs.
     * One that a command does not have binds {@code null}, or an empty array, rather than skipping its
     * arm: {@code = null} matches no row, the same zero rows a skipped arm would have produced, and a
     * prepared statement cannot have an optional arm.
     *
     * <p>{@code target_lines} is a CTE because two arms read it — the lines arm, and the stock arm,
     * whose scope is {@link Command#declaredSkus()} plus whatever SKUs those lines add, which {@link
     * #readSnapshot} only knows after a separate read of them. One statement can still compute it,
     * because a subquery in it runs against the same snapshot as everything else in it — the whole
     * reason this needs no transaction (see {@link #load}). The scope is one array and the stock arm
     * asks for {@code sku = any(...)}: a row matches once however often the array names its SKU, so
     * a SKU the command and the reservation both name comes back once, with every shard it has.
     *
     * <p>It returns its rows in no order, and that is what keeps it cheap. It used to number the
     * lines and the stock with window functions, sort the lot by {@code kind} and join the stock to a
     * deduplicated set of SKUs; for the dozen rows a load returns, that cost the database more than
     * the lookups did (ADR 12, "Later"). Nothing needs the order: {@link #assembleLeanSnapshot} puts
     * the rows into a {@link Snapshot} and a {@link Reservation}, whose constructors sort what they
     * are given. {@code JdbcLedgerTest} keeps the statement this way, holding its generic plan to
     * lookups under one append and holding it to being planned once rather than on every call, which
     * a rewrite that runs faster can lose.
     */
    private static final String SNAPSHOT_QUERY =
            "with target_lines as ("
                    + "  select sku, shard, quantity from till_reservation_line where reservation_id = ?"
                    + ") "
                    + "select 'record' as kind, idem_key::text as text1, fingerprint::text as text2, "
                    + "       outcome::text as text3, null::integer as int1, null::bigint as num1, "
                    + "       null::bigint as num2, null::bigint as num3, recorded_at as ts1, "
                    + "       null::timestamptz as ts2 "
                    + "from till_idempotency where idem_key = ? "
                    + "union all "
                    + "select 'reservation', id::text, idem_key::text, state::text, null::integer, "
                    + "       version, null::bigint, null::bigint, created_at, expires_at "
                    + "from till_reservation where id = ? "
                    + "union all "
                    + "select 'line', sku::text, null::text, null::text, shard, quantity, null::bigint, "
                    + "       null::bigint, null::timestamptz, null::timestamptz "
                    + "from target_lines "
                    + "union all "
                    + "select 'stock', sku::text, null::text, null::text, shard, on_hand, reserved, "
                    + "       version, null::timestamptz, null::timestamptz "
                    + "from till_stock where sku = any(?::varchar[] || array(select sku from target_lines))";

    /**
     * Everything a batch's load ({@link #loadBatch}) reads, in one statement: {@link #SNAPSHOT_QUERY}
     * with an array wherever it has a scalar — the records of every key the commands carry, every
     * reservation they name and the lines of all of those, and the stock of every SKU they name or
     * those reservations hold. The same arms and the same columns, read by
     * {@link #assembleBatchSnapshot}, with one more column in use: a line's {@code text2} is the
     * reservation it belongs to, which a load of one reservation never needed.
     *
     * <p>The parameters, in the order they appear: the reservations named (for {@code target_lines}),
     * the keys, the reservations named again (for their headers), and the SKUs the commands name.
     * Like the statement it is built from, it returns its rows in no order, and nothing depends on one.
     */
    private static final String BATCH_SNAPSHOT_QUERY =
            "with target_lines as ("
                    + "  select reservation_id, sku, shard, quantity from till_reservation_line "
                    + "  where reservation_id = any(?::varchar[])"
                    + ") "
                    + "select 'record' as kind, idem_key::text as text1, fingerprint::text as text2, "
                    + "       outcome::text as text3, null::integer as int1, null::bigint as num1, "
                    + "       null::bigint as num2, null::bigint as num3, recorded_at as ts1, "
                    + "       null::timestamptz as ts2 "
                    + "from till_idempotency where idem_key = any(?::varchar[]) "
                    + "union all "
                    + "select 'reservation', id::text, idem_key::text, state::text, null::integer, "
                    + "       version, null::bigint, null::bigint, created_at, expires_at "
                    + "from till_reservation where id = any(?::varchar[]) "
                    + "union all "
                    + "select 'line', sku::text, reservation_id::text, null::text, shard, quantity, null::bigint, "
                    + "       null::bigint, null::timestamptz, null::timestamptz "
                    + "from target_lines "
                    + "union all "
                    + "select 'stock', sku::text, null::text, null::text, shard, on_hand, reserved, "
                    + "       version, null::timestamptz, null::timestamptz "
                    + "from till_stock where sku = any(?::varchar[] || array(select sku from target_lines))";

    /**
     * Ordering is {@code collate "C"} throughout, which is code point order and therefore the order
     * Java sorts these strings in. A database created with a language collation sorts punctuation
     * differently, and two ledgers that offer the same rows in different orders cannot be compared
     * against each other by the differential test.
     */
    private static final String SELECT_RECLAIMABLE_FOR_SKUS =
            "select r.id, r.idem_key, r.state, r.created_at, r.expires_at, r.version from till_reservation r "
                    + "where r.state = 'HELD' and r.expires_at <= ? and r.id <> coalesce(?, '') "
                    + "and exists (select 1 from till_reservation_line l "
                    + "            where l.reservation_id = r.id and l.sku = any(?)) "
                    + "order by r.id collate \"C\" limit ?";

    private static final String SELECT_RECLAIMABLE_ANY =
            "select id, idem_key, state, created_at, expires_at, version from till_reservation "
                    + "where state = 'HELD' and expires_at <= ? order by id collate \"C\" limit ?";

    /**
     * A whole decision, or a whole batch's, written by {@code till_apply} ({@code V6__apply_batches.sql}):
     * every stock row, state change, new reservation and its lines, event and record as arrays, a
     * column of them to an argument, and whether it has to be on disk before the call returns. The
     * text is the same for every decision and every batch, whatever it holds, so the driver prepares
     * it once per connection and the server plans it once. The arguments are named, so the call says
     * which column each placeholder is; {@link #bind} sets them in this order. The idempotency record
     * goes in the {@code records_} arrays, a decision's one record as a batch's many; V5's scalar
     * {@code record_} arguments are left to their defaults, there for the last version's call.
     */
    private static final String APPLY =
            "select till_apply("
                    + "put_sku => ?, put_shard => ?, put_on_hand => ?, put_reserved => ?, put_version => ?, "
                    + "put_new_version => ?, "
                    + "set_id => ?, set_state => ?, set_version => ?, "
                    + "insert_id => ?, insert_key => ?, insert_state => ?, insert_created_at => ?, "
                    + "insert_expires_at => ?, insert_version => ?, "
                    + "line_reservation => ?, line_sku => ?, line_shard => ?, line_quantity => ?, "
                    + "event_key => ?, event_payload => ?, event_recorded_at => ?, "
                    + "records_key => ?, records_fingerprint => ?, records_outcome => ?, records_recorded_at => ?, "
                    + "durable => ?)";

    /**
     * The outcomes whose decisions commit without waiting for their WAL to be flushed (ADR 15): a hold
     * taken, a hold given back, holds written off by the sweep, and a refusal, which writes nothing but
     * the expired holds it wrote off on the way and its own record. A hold is temporary by design, and
     * a crash that loses one of these leaves the database as though it had not been made: a hold that
     * was never taken, a hold still there that expires again later (ADR 4), a command that a retry
     * decides afresh. Nothing durable can depend on one and survive without it, because a durable
     * commit flushes all the WAL before its own.
     *
     * <p>Everything else waits for the flush: a hold turned into a sale, which lost would let a unit
     * that was paid for be sold again; on-hand stock adjusted, which lost could invent stock; a SKU
     * split into shards; and any outcome added after this was written, until somebody decides
     * otherwise.
     */
    private static final Set<Class<? extends Outcome>> ASYNCHRONOUS =
            Set.of(Outcome.Reserved.class, Outcome.Released.class, Outcome.Swept.class, Outcome.Rejected.class);

    private static final String SELECT_UNPUBLISHED =
            "select sequence, payload, recorded_at from till_outbox where published_at is null "
                    + "order by sequence limit ?";

    private static final String MARK_PUBLISHED =
            "update till_outbox set published_at = ? where sequence = any(?) and published_at is null";

    /**
     * The publishing claim: a transaction-scoped advisory lock, so it goes with the transaction —
     * committed, rolled back, or dropped with the connection of a publisher that died holding it.
     */
    private static final String CLAIM_PUBLISHING = "select pg_try_advisory_xact_lock(?)";

    /** "tilloutb" in ASCII. Any fixed number would do; this one says whose it is in {@code pg_locks}. */
    private static final long PUBLISHING_LOCK = 0x74696c6c6f757462L;

    /**
     * Flushes the server's WAL to disk as far as it has been written, before a batch is sent: past the
     * commit of every row the publisher has just read. A hold commits without waiting for that flush
     * (ADR 15), and any connection can read its event from the moment it commits; a crash before the
     * flush takes the hold back, and an event already sent cannot be. The statement writes a logical
     * message that nothing decodes, outside the transaction, and flushes it at once
     * ({@code flush => true}, from PostgreSQL 17). A commit would flush as well, but the claim is this
     * transaction's, and committing would give it up in the middle of a batch.
     */
    private static final String FLUSH_WAL = "select pg_logical_emit_message(false, 'till.outbox', '', true)";

    /** A SKU's level is its shards added up; the version too, so it moves when any of them does. */
    private static final String LEVELS =
            "select sku, sum(on_hand) as on_hand, sum(reserved) as reserved, sum(version) as version, "
                    + "count(*) as shards from till_stock ";

    private static final String LIST_STOCK =
            LEVELS + "where sku collate \"C\" > coalesce(?, '') group by sku order by sku collate \"C\" limit ?";

    private static final String ALL_STOCK = LEVELS + "group by sku order by sku collate \"C\"";

    private static final String ALL_SHARDS =
            "select sku, shard, on_hand, reserved, version from till_stock order by sku collate \"C\", shard";

    /**
     * Newest first, with the id breaking ties so that two holds created in the same microsecond come
     * back in a fixed order. The state filter is a parameter rather than two statements because
     * {@code state = coalesce(?, state)} lets PostgreSQL plan one query for both shapes.
     */
    private static final String LIST_RESERVATIONS =
            "select id, idem_key, state, created_at, expires_at, version from till_reservation "
                    + "where state = coalesce(?, state) order by created_at desc, id limit ?";

    /**
     * Bounded deletes, expressed as a self-join on the primary key.
     *
     * <p>`delete ... where id in (select ... limit ?)` rather than `delete ... limit ?`, which
     * PostgreSQL does not support. The subquery is ordered so the oldest go first and a run that is
     * interrupted has still made progress from the right end.
     *
     * <p>This one additionally refuses to forget a key while an event named after it is still in
     * the outbox.
     *
     * <p>An adjustment's deduplication key is {@code adjusted:<idempotency-key>}, because an
     * adjustment has no identity of its own. That key is unique only for as long as the ledger
     * remembers the idempotency key: forget the record while the event survives, and the next
     * execution of that command writes an event whose key already exists — the insert conflicts,
     * the decision cannot be applied, and the caller is told 503 forever.
     *
     * <p>Found by writing the retention test, which is exactly what it looked like: a command that
     * had worked an hour earlier became permanently impossible.
     */
    private static final String FORGET_IDEMPOTENCY =
            "delete from till_idempotency where idem_key in ("
                    + "  select i.idem_key from till_idempotency i where i.recorded_at < ? "
                    + "    and not exists (select 1 from till_outbox o where o.dedupe_key = ? || i.idem_key) "
                    + "  order by i.recorded_at limit ?)";

    private static final String PRUNE_OUTBOX =
            "delete from till_outbox where sequence in ("
                    + "  select sequence from till_outbox "
                    + "  where published_at is not null and published_at < ? "
                    + "  order by sequence limit ?)";

    /** Lines go with it: the foreign key cascades. */
    private static final String PRUNE_RESERVATIONS =
            "delete from till_reservation where id in ("
                    + "  select id from till_reservation "
                    + "  where state <> 'HELD' and expires_at < ? "
                    + "  order by expires_at limit ?)";

    /** PostgreSQL's SQLState for a check constraint violation, which is a bug and not contention. */
    private static final String CHECK_VIOLATION = "23514";

    /**
     * What {@code till_apply} raises, and nothing else does, when a row is not at the version the
     * decision expects or a key it inserts is taken: contention, which {@code apply} answers with
     * {@code false}. A class of SQLState PostgreSQL does not use, so that no error of its own can be
     * read as one.
     */
    private static final String REFUSED = "TL001";

    private final DataSource dataSource;

    /**
     * @param dataSource a pool against a PostgreSQL database with the till schema applied
     */
    public JdbcLedger(DataSource dataSource) {
        if (dataSource == null) {
            throw new IllegalArgumentException("dataSource is required");
        }
        this.dataSource = dataSource;
    }

    /**
     * {@inheritDoc}
     *
     * <p>{@code reclaimLimit == 0} — every reserve, commit and release's first try, and the common
     * case by far (ADR 12) — takes {@link #loadLean}: one statement, in autocommit, no transaction of
     * its own. A PostgreSQL statement takes its snapshot once, when it starts, and every subquery and
     * {@code UNION ALL} arm in it shares that snapshot, so one statement already reads at one instant
     * without a transaction to say so.
     *
     * <p>A reclaiming load and the sweep still open one. The transaction sets its own isolation, as
     * its first statement, rather than the connection's being changed around it. Changing the
     * connection costs a statement to ask what it was, one to set it, and one to put it back
     * afterwards, each a transaction of its own; the fourth load test spent more of the database's
     * commits on those than on everything else together (docs/load-test.md).
     */
    @Override
    public Snapshot load(Command command, Instant now, int reclaimLimit) {
        if (reclaimLimit == 0) {
            return loadLean(command);
        }
        try (Connection connection = dataSource.getConnection()) {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                try (Statement statement = connection.createStatement()) {
                    statement.execute(SNAPSHOT_TRANSACTION);
                }
                Snapshot snapshot = readSnapshot(connection, command, now, reclaimLimit);
                connection.commit();
                return snapshot;
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(autoCommit);
            }
        } catch (SQLException e) {
            throw new LedgerException("loading a snapshot for " + command, e);
        }
    }

    /**
     * The lean load: {@link #SNAPSHOT_QUERY} in one round trip, no transaction, because a reclaim
     * limit of zero means {@link #readReclaimable} would return nothing without issuing a statement
     * anyway (see its first line) — so the only reads a lean load ever needs are the idempotency
     * record, the named reservation and its lines, and the stock of the resulting scope, and all four
     * fit in one statement.
     */
    private Snapshot loadLean(Command command) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(SNAPSHOT_QUERY)) {
            String targetId = command.targetReservation().map(ReservationId::value).orElse(null);
            statement.setString(1, targetId);
            statement.setString(2, command.idempotencyKey().map(IdempotencyKey::value).orElse(null));
            statement.setString(3, targetId);
            statement.setArray(4, skuArray(connection, command.declaredSkus()));
            try (ResultSet rows = statement.executeQuery()) {
                return assembleLeanSnapshot(command, rows);
            }
        } catch (SQLException e) {
            throw new LedgerException("loading a snapshot for " + command, e);
        }
    }

    /**
     * Reads {@link #SNAPSHOT_QUERY}'s rows, discriminated by {@code kind}, into the same
     * {@link Snapshot} {@link #readSnapshot} would build from four separate reads.
     *
     * <p>Column layout, read by {@code kind}; a column a kind does not use is {@code null} and never
     * read:
     *
     * <ul>
     *   <li>{@code record}: {@code text1}=idem_key, {@code text2}=fingerprint, {@code text3}=outcome,
     *       {@code ts1}=recorded_at
     *   <li>{@code reservation}: {@code text1}=id, {@code text2}=idem_key, {@code text3}=state,
     *       {@code num1}=version, {@code ts1}=created_at, {@code ts2}=expires_at
     *   <li>{@code line}: {@code text1}=sku, {@code int1}=shard, {@code num1}=quantity
     *   <li>{@code stock}: {@code text1}=sku, {@code int1}=shard, {@code num1}=on_hand,
     *       {@code num2}=reserved, {@code num3}=version
     * </ul>
     *
     * <p>The scope — every SKU {@link Snapshot#stock()} must have an entry for — is recomputed here
     * from the command's own SKUs and the reservation found, exactly as {@link #readSnapshot} does,
     * rather than read off the rows: the statement's array only decides which stock rows to fetch,
     * and a SKU with none still has to be marked {@linkplain Snapshot.Builder#absent absent}.
     *
     * <p>The rows arrive in no order (see {@link #SNAPSHOT_QUERY}), and nothing here depends on one:
     * the {@link Snapshot} sorts each SKU's shards by index, the {@link Reservation} sorts its lines
     * by SKU and its allocations by SKU and shard, and the SKUs of the snapshot follow {@code scope},
     * which is the command's own order and then the reservation's.
     */
    private Snapshot assembleLeanSnapshot(Command command, ResultSet rows) throws SQLException {
        Snapshot.Builder builder = Snapshot.builder();
        Row header = null;
        List<Allocation> lines = new ArrayList<>();
        Map<Sku, List<StockShard>> stock = new LinkedHashMap<>();

        while (rows.next()) {
            switch (rows.getString("kind")) {
                case "record" ->
                        builder.recordedOutcome(
                                new OutcomeRecord(
                                        IdempotencyKey.of(rows.getString("text1")),
                                        rows.getString("text2"),
                                        rows.getString("text3"),
                                        instant(rows, "ts1")));
                case "reservation" ->
                        header =
                                new Row(
                                        rows.getString("text1"),
                                        rows.getString("text2"),
                                        rows.getString("text3"),
                                        instant(rows, "ts1"),
                                        instant(rows, "ts2"),
                                        rows.getLong("num1"));
                case "line" ->
                        lines.add(
                                new Allocation(Sku.of(rows.getString("text1")), rows.getInt("int1"), rows.getLong("num1")));
                case "stock" -> {
                    Sku sku = Sku.of(rows.getString("text1"));
                    stock.computeIfAbsent(sku, ignored -> new ArrayList<>())
                            .add(
                                    new StockShard(
                                            sku,
                                            rows.getInt("int1"),
                                            rows.getLong("num1"),
                                            rows.getLong("num2"),
                                            rows.getLong("num3")));
                }
                default ->
                        throw new IllegalStateException(
                                "snapshot query returned an unknown row kind " + rows.getString("kind"));
            }
        }

        Set<Sku> scope = new LinkedHashSet<>(command.declaredSkus());
        if (header != null) {
            Reservation named = header.toReservation(requireLines(lines, ReservationId.of(header.id)));
            builder.reservation(named);
            scope.addAll(named.skus());
        }

        for (Sku sku : scope) {
            builder.absent(sku);
            stock.getOrDefault(sku, List.of()).forEach(builder::shard);
        }
        return builder.build();
    }

    private Snapshot readSnapshot(Connection connection, Command command, Instant now, int reclaimLimit)
            throws SQLException {
        Snapshot.Builder builder = Snapshot.builder();

        Optional<IdempotencyKey> key = command.idempotencyKey();
        if (key.isPresent()) {
            readRecord(connection, key.get()).ifPresent(builder::recordedOutcome);
        }

        Set<Sku> scope = new LinkedHashSet<>(command.declaredSkus());

        Reservation named = null;
        Optional<ReservationId> target = command.targetReservation();
        if (target.isPresent()) {
            named = readReservation(connection, target.get()).orElse(null);
            if (named != null) {
                builder.reservation(named);
                scope.addAll(named.skus());
            }
        }

        List<Reservation> reclaimable = readReclaimable(connection, command, now, scope, reclaimLimit, named);
        reclaimable.forEach(reservation -> scope.addAll(reservation.skus()));
        builder.reclaimable(reclaimable);

        Map<Sku, List<StockShard>> levels = readStock(connection, scope);
        for (Sku sku : scope) {
            builder.absent(sku);
            levels.getOrDefault(sku, List.of()).forEach(builder::shard);
        }
        return builder.build();
    }

    private Optional<OutcomeRecord> readRecord(Connection connection, IdempotencyKey key) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(SELECT_RECORD)) {
            statement.setString(1, key.value());
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return Optional.empty();
                }
                return Optional.of(
                        new OutcomeRecord(
                                IdempotencyKey.of(rows.getString("idem_key")),
                                rows.getString("fingerprint"),
                                rows.getString("outcome"),
                                instant(rows, "recorded_at")));
            }
        }
    }

    private Optional<Reservation> readReservation(Connection connection, ReservationId id) throws SQLException {
        Row row;
        try (PreparedStatement statement = connection.prepareStatement(SELECT_RESERVATION)) {
            statement.setString(1, id.value());
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return Optional.empty();
                }
                row = readRow(rows);
            }
        }
        Map<ReservationId, List<Allocation>> lines = readLines(connection, List.of(id));
        return Optional.of(row.toReservation(requireLines(lines, id)));
    }

    private List<Reservation> readReclaimable(
            Connection connection,
            Command command,
            Instant now,
            Set<Sku> scope,
            int limit,
            Reservation named)
            throws SQLException {
        if (limit <= 0) {
            return List.of();
        }
        boolean sweep = command instanceof Command.Sweep;
        if (!sweep && scope.isEmpty()) {
            return List.of();
        }

        List<Row> rows = new ArrayList<>();
        try (PreparedStatement statement =
                connection.prepareStatement(sweep ? SELECT_RECLAIMABLE_ANY : SELECT_RECLAIMABLE_FOR_SKUS)) {
            statement.setObject(1, offset(now));
            if (sweep) {
                statement.setInt(2, limit);
            } else {
                statement.setString(2, named != null ? named.id().value() : null);
                statement.setArray(3, skuArray(connection, scope));
                statement.setInt(4, limit);
            }
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    rows.add(readRow(result));
                }
            }
        }
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<ReservationId, List<Allocation>> lines =
                readLines(connection, rows.stream().map(row -> ReservationId.of(row.id)).toList());
        List<Reservation> reservations = new ArrayList<>(rows.size());
        for (Row row : rows) {
            reservations.add(row.toReservation(requireLines(lines, ReservationId.of(row.id))));
        }
        return reservations;
    }

    /** Each hold's allocations: what it took, from which shard of which SKU. */
    private Map<ReservationId, List<Allocation>> readLines(Connection connection, List<ReservationId> ids)
            throws SQLException {
        Map<ReservationId, List<Allocation>> lines = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(SELECT_LINES)) {
            statement.setArray(1, idArray(connection, ids));
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    lines.computeIfAbsent(ReservationId.of(rows.getString("reservation_id")), ignored -> new ArrayList<>())
                            .add(new Allocation(Sku.of(rows.getString("sku")), rows.getInt("shard"), rows.getLong("quantity")));
                }
            }
        }
        return lines;
    }

    private static List<Allocation> requireLines(Map<ReservationId, List<Allocation>> lines, ReservationId id) {
        return requireLines(lines.getOrDefault(id, List.of()), id);
    }

    /** As {@link #requireLines(Map, ReservationId)}, for a caller that already has one reservation's. */
    private static List<Allocation> requireLines(List<Allocation> lines, ReservationId id) {
        if (lines.isEmpty()) {
            // The foreign key makes orphaned lines impossible; a reservation with none means
            // somebody wrote the header without them, which is not a state to paper over.
            throw new IllegalStateException("reservation " + id + " has no lines");
        }
        return lines;
    }

    private Map<Sku, List<StockShard>> readStock(Connection connection, Set<Sku> skus) throws SQLException {
        Map<Sku, List<StockShard>> levels = new LinkedHashMap<>();
        if (skus.isEmpty()) {
            return levels;
        }
        try (PreparedStatement statement = connection.prepareStatement(SELECT_STOCK)) {
            statement.setArray(1, skuArray(connection, skus));
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    StockShard shard = readShard(rows);
                    levels.computeIfAbsent(shard.sku(), ignored -> new ArrayList<>()).add(shard);
                }
            }
        }
        return levels;
    }

    /**
     * {@inheritDoc}
     *
     * <p>One statement, in autocommit: {@code till_apply} takes the decision as arrays and writes it,
     * and the statement that calls it is the whole transaction, begun, written and committed inside
     * one round trip. No row the decision has written stays locked while another statement crosses
     * the network, as each did when every mutation was a statement of its own between a {@code BEGIN}
     * and a {@code COMMIT} (ADR 14).
     *
     * <p>A decision whose outcome is in {@link #ASYNCHRONOUS} is committed with {@code
     * synchronous_commit} off, for its own transaction only, and {@code true} means it is committed
     * and visible, not yet that it is on disk: a crash of the database in the next few hundred
     * milliseconds can lose it, whole. Every other decision is on disk when this returns (ADR 15).
     *
     * <p>A row not at the version the decision expects, or a key it inserts already taken, makes the
     * function raise {@link #REFUSED}, which undoes everything the statement wrote: that is the
     * {@code false}. Every other error is an exception, a check violation above all.
     *
     * @throws IllegalArgumentException if the decision lists its mutations in an order other than the
     *     kernel's — stock rows, then state changes, then new reservations — which the function could
     *     not keep; nothing is sent
     */
    @Override
    public boolean apply(Decision decision) {
        requireKernelOrder(decision);
        // A decision is a batch of one: the same statement, the same function, the same arguments.
        return write(BatchDecision.of(List.of(decision)), "applying " + decision.outcome());
    }

    /**
     * {@inheritDoc}
     *
     * <p>One statement, in autocommit, as {@link #apply} is: the same call to {@code till_apply},
     * with every row of the batch in its arrays. Each stock row is written once, at the version the
     * batch's decisions would have left it at one at a time; a reservation created and finished in the
     * batch is inserted as it was left; every command's record goes in. The batch waits for the disk
     * if any of its decisions has to ({@link #ASYNCHRONOUS}, ADR 15): with a sale in it, it commits as
     * a sale does.
     *
     * <p>A row not at the version the batch read, or a key it inserts already taken, is {@code false},
     * and nothing of the batch is written; every other error is an exception (ADR 16).
     */
    @Override
    public boolean applyBatch(BatchDecision decision) {
        return write(decision, "applying a batch of " + decision.outcomes().size() + " decisions");
    }

    /**
     * {@inheritDoc}
     *
     * <p>One statement, in autocommit, built like the lean load ({@link #BATCH_SNAPSHOT_QUERY}), so
     * the whole batch reads at one instant without a transaction to say so.
     */
    @Override
    public BatchSnapshot loadBatch(List<Command> commands) {
        Set<String> targets = new LinkedHashSet<>();
        Set<String> keys = new LinkedHashSet<>();
        Set<Sku> declared = new LinkedHashSet<>();
        for (Command command : commands) {
            command.targetReservation().ifPresent(id -> targets.add(id.value()));
            command.idempotencyKey().ifPresent(key -> keys.add(key.value()));
            declared.addAll(command.declaredSkus());
        }
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(BATCH_SNAPSHOT_QUERY)) {
            statement.setArray(1, connection.createArrayOf("varchar", targets.toArray()));
            statement.setArray(2, connection.createArrayOf("varchar", keys.toArray()));
            statement.setArray(3, connection.createArrayOf("varchar", targets.toArray()));
            statement.setArray(4, skuArray(connection, declared));
            try (ResultSet rows = statement.executeQuery()) {
                return assembleBatchSnapshot(commands, rows);
            }
        } catch (SQLException e) {
            throw new LedgerException("loading a snapshot for a batch of " + commands.size() + " commands", e);
        }
    }

    /**
     * Reads {@link #BATCH_SNAPSHOT_QUERY}'s rows into the {@link BatchSnapshot} an
     * {@link io.till.core.mem.InMemoryLedger} would have read: the columns as
     * {@link #assembleLeanSnapshot} reads them, a line's reservation in {@code text2}.
     *
     * <p>Everything is put in the order the commands name it — their keys, their reservations, their
     * SKUs and then their reservations' — rather than the order the rows arrived in, which is none.
     */
    private BatchSnapshot assembleBatchSnapshot(List<Command> commands, ResultSet rows) throws SQLException {
        Map<IdempotencyKey, OutcomeRecord> records = new HashMap<>();
        Map<ReservationId, Row> headers = new HashMap<>();
        Map<ReservationId, List<Allocation>> lines = new HashMap<>();
        Map<Sku, List<StockShard>> stock = new HashMap<>();
        while (rows.next()) {
            switch (rows.getString("kind")) {
                case "record" -> {
                    OutcomeRecord record = new OutcomeRecord(
                            IdempotencyKey.of(rows.getString("text1")),
                            rows.getString("text2"),
                            rows.getString("text3"),
                            instant(rows, "ts1"));
                    records.put(record.key(), record);
                }
                case "reservation" -> {
                    Row header = new Row(
                            rows.getString("text1"),
                            rows.getString("text2"),
                            rows.getString("text3"),
                            instant(rows, "ts1"),
                            instant(rows, "ts2"),
                            rows.getLong("num1"));
                    headers.put(ReservationId.of(header.id()), header);
                }
                case "line" -> lines.computeIfAbsent(ReservationId.of(rows.getString("text2")), ignored -> new ArrayList<>())
                        .add(new Allocation(Sku.of(rows.getString("text1")), rows.getInt("int1"), rows.getLong("num1")));
                case "stock" -> {
                    Sku sku = Sku.of(rows.getString("text1"));
                    stock.computeIfAbsent(sku, ignored -> new ArrayList<>()).add(new StockShard(
                            sku, rows.getInt("int1"), rows.getLong("num1"), rows.getLong("num2"), rows.getLong("num3")));
                }
                default -> throw new IllegalStateException(
                        "a batch's snapshot query returned an unknown row kind " + rows.getString("kind"));
            }
        }

        Map<IdempotencyKey, OutcomeRecord> used = new LinkedHashMap<>();
        Map<ReservationId, Reservation> named = new LinkedHashMap<>();
        Set<Sku> scope = new LinkedHashSet<>();
        for (Command command : commands) {
            command.idempotencyKey().filter(records::containsKey).ifPresent(key -> used.put(key, records.get(key)));
            scope.addAll(command.declaredSkus());
            Optional<ReservationId> target = command.targetReservation().filter(headers::containsKey);
            if (target.isPresent()) {
                Reservation reservation = named.computeIfAbsent(
                        target.get(), id -> headers.get(id).toReservation(requireLines(lines, id)));
                scope.addAll(reservation.skus());
            }
        }
        Map<Sku, List<StockShard>> levels = new LinkedHashMap<>();
        for (Sku sku : scope) {
            levels.put(sku, stock.getOrDefault(sku, List.of()));
        }
        return new BatchSnapshot(levels, named, used);
    }

    /**
     * Writes a batch, a decision's included, with one call to {@code till_apply}, in autocommit.
     * {@code false} for {@link #REFUSED}; every other error an exception, a check violation above all.
     */
    private boolean write(BatchDecision decision, String what) {
        boolean durable = decision.outcomes().stream().anyMatch(outcome -> !ASYNCHRONOUS.contains(outcome.getClass()));
        try (Connection connection = dataSource.getConnection()) {
            boolean autoCommit = connection.getAutoCommit();
            if (!autoCommit) {
                // Only in autocommit is the statement its own transaction. On a connection that is
                // not, the decision would be written into a transaction nobody commits.
                connection.setAutoCommit(true);
            }
            try (PreparedStatement statement = connection.prepareStatement(APPLY)) {
                bind(connection, statement, decision, durable);
                statement.execute();
                return true;
            } finally {
                if (!autoCommit) {
                    connection.setAutoCommit(false);
                }
            }
        } catch (SQLException e) {
            if (REFUSED.equals(e.getSQLState())) {
                return false;
            }
            if (CHECK_VIOLATION.equals(e.getSQLState())) {
                // The database refused a level the kernel should never have produced. Retrying
                // would spin around the bug rather than report it.
                throw new LedgerException(
                        "the database refused an impossible stock level, which is a bug rather than contention", e);
            }
            throw new LedgerException(what, e);
        }
    }

    /** Binds a batch's rows, and whether it is durable, to {@link #APPLY}, in the order its placeholders are in. */
    private static void bind(Connection connection, PreparedStatement statement, BatchDecision decision, boolean durable)
            throws SQLException {
        List<BatchDecision.StockWrite> puts = decision.stock();
        List<LineRow> lines = new ArrayList<>();
        for (Reservation reservation : decision.inserts()) {
            reservation.allocations().forEach(allocation -> lines.add(new LineRow(reservation.id(), allocation)));
        }
        List<OutcomeRecord> records = decision.records();
        int at = 0;
        statement.setArray(++at, array(connection, "varchar", puts, put -> put.sku().value()));
        statement.setArray(++at, array(connection, "integer", puts, BatchDecision.StockWrite::shard));
        statement.setArray(++at, array(connection, "bigint", puts, BatchDecision.StockWrite::onHand));
        statement.setArray(++at, array(connection, "bigint", puts, BatchDecision.StockWrite::reserved));
        // No version for a row the decision creates: the kernel's ABSENT stays the kernel's.
        statement.setArray(++at, array(connection, "bigint", puts, put -> put.isInsert() ? null : put.expectedVersion()));
        statement.setArray(++at, array(connection, "bigint", puts, BatchDecision.StockWrite::newVersion));
        statement.setArray(++at, array(connection, "varchar", decision.states(), set -> set.reservationId().value()));
        statement.setArray(++at, array(connection, "varchar", decision.states(), set -> set.state().name()));
        statement.setArray(
                ++at, array(connection, "bigint", decision.states(), Mutation.SetReservationState::expectedVersion));
        statement.setArray(++at, array(connection, "varchar", decision.inserts(), held -> held.id().value()));
        statement.setArray(++at, array(connection, "varchar", decision.inserts(), held -> held.key().value()));
        statement.setArray(++at, array(connection, "varchar", decision.inserts(), held -> held.state().name()));
        statement.setArray(++at, array(connection, "timestamptz", decision.inserts(), held -> text(held.createdAt())));
        statement.setArray(++at, array(connection, "timestamptz", decision.inserts(), held -> text(held.expiresAt())));
        statement.setArray(++at, array(connection, "bigint", decision.inserts(), Reservation::version));
        statement.setArray(++at, array(connection, "varchar", lines, line -> line.reservation().value()));
        statement.setArray(++at, array(connection, "varchar", lines, line -> line.allocation().sku().value()));
        statement.setArray(++at, array(connection, "integer", lines, line -> line.allocation().shard()));
        statement.setArray(++at, array(connection, "bigint", lines, line -> line.allocation().quantity()));
        statement.setArray(++at, array(connection, "varchar", decision.events(), Event::dedupeKey));
        statement.setArray(++at, array(connection, "text", decision.events(), Codec::encodeEvent));
        statement.setArray(++at, array(connection, "timestamptz", decision.events(), event -> text(event.occurredAt())));
        statement.setArray(++at, array(connection, "varchar", records, record -> record.key().value()));
        statement.setArray(++at, array(connection, "varchar", records, OutcomeRecord::fingerprint));
        statement.setArray(++at, array(connection, "text", records, OutcomeRecord::encodedOutcome));
        statement.setArray(++at, array(connection, "timestamptz", records, record -> text(record.recordedAt())));
        statement.setBoolean(++at, durable);
    }

    /** One column of one kind of row, as the SQL array {@code till_apply} takes it. */
    private static <T> Array array(Connection connection, String type, List<T> rows, Function<? super T, ?> column)
            throws SQLException {
        return connection.createArrayOf(type, rows.stream().map(column).toArray());
    }

    /**
     * An instant as an element of a {@code timestamptz} array: ISO-8601 at UTC. PostgreSQL keeps
     * microseconds, and {@link io.till.core.Till} truncates to them before deciding, so the instant
     * read back is the one written.
     */
    private static String text(Instant instant) {
        return instant.toString();
    }

    /**
     * {@inheritDoc}
     *
     * <p>What is committed, which can include the events of holds not yet on disk (ADR 15): fine for
     * looking at the tail, as the operator's view does, and not for sending. A publisher built on this
     * and {@link #markPublished} has to flush the WAL between reading and sending, as {@link
     * #publishNext} does.
     */
    @Override
    public List<OutboxEntry> unpublished(int limit) {
        try (Connection connection = dataSource.getConnection()) {
            return readUnpublished(connection, limit);
        } catch (SQLException e) {
            throw new LedgerException("reading the outbox", e);
        }
    }

    private static List<OutboxEntry> readUnpublished(Connection connection, int limit) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(SELECT_UNPUBLISHED)) {
            statement.setInt(1, limit);
            List<OutboxEntry> entries = new ArrayList<>();
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    entries.add(
                            new OutboxEntry(
                                    rows.getLong("sequence"),
                                    Codec.decodeEvent(rows.getString("payload")),
                                    instant(rows, "recorded_at")));
                }
            }
            return entries;
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>The batch is read, published and marked in one transaction, which holds its connection while
     * the batch is sent — the one place the ledger does, because the claim has to last exactly as
     * long as the send. It locks no row: the command path only ever inserts into the outbox, and
     * another publisher that finds the claim taken goes away rather than waiting.
     *
     * <p>Between reading the batch and sending it, the WAL is flushed ({@link #FLUSH_WAL}), so that no
     * event leaves while a crash of the database could still take back the decision that wrote it.
     */
    @Override
    public OptionalInt publishNext(int limit, Instant at, Consumer<List<OutboxEntry>> publish) {
        try (Connection connection = dataSource.getConnection()) {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                if (!claim(connection)) {
                    connection.rollback();
                    return OptionalInt.empty();
                }
                List<OutboxEntry> batch = readUnpublished(connection, limit);
                if (!batch.isEmpty()) {
                    flushWal(connection);
                    publish.accept(batch);
                    try (PreparedStatement statement = connection.prepareStatement(MARK_PUBLISHED)) {
                        statement.setObject(1, offset(at));
                        statement.setArray(
                                2, connection.createArrayOf("bigint", batch.stream().map(OutboxEntry::sequence).toArray()));
                        statement.executeUpdate();
                    }
                }
                connection.commit();
                return OptionalInt.of(batch.size());
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(autoCommit);
            }
        } catch (SQLException e) {
            throw new LedgerException("publishing the outbox", e);
        }
    }

    private static void flushWal(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(FLUSH_WAL)) {
            statement.execute();
        }
    }

    private static boolean claim(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(CLAIM_PUBLISHING)) {
            statement.setLong(1, PUBLISHING_LOCK);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() && rows.getBoolean(1);
            }
        }
    }

    @Override
    public long backlog() {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement("select count(*) from till_outbox where published_at is null");
                ResultSet rows = statement.executeQuery()) {
            return rows.next() ? rows.getLong(1) : 0;
        } catch (SQLException e) {
            throw new LedgerException("counting the outbox backlog", e);
        }
    }

    @Override
    public void markPublished(List<Long> sequences, Instant at) {
        if (sequences.isEmpty()) {
            return;
        }
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(MARK_PUBLISHED)) {
            statement.setObject(1, offset(at));
            statement.setArray(2, connection.createArrayOf("bigint", sequences.toArray()));
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new LedgerException("marking outbox rows published", e);
        }
    }

    @Override
    public int forgetIdempotency(Instant before, int limit) {
        requireBatch(limit);
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(FORGET_IDEMPOTENCY)) {
            statement.setObject(1, offset(before));
            // The prefix comes from the kernel rather than being spelled out here, so that changing
            // the key's shape breaks a compile instead of quietly breaking this guard.
            statement.setString(2, Event.StockAdjusted.DEDUPE_PREFIX);
            statement.setInt(3, limit);
            return statement.executeUpdate();
        } catch (SQLException e) {
            throw new LedgerException("deleting idempotency records older than " + before, e);
        }
    }

    @Override
    public int pruneOutbox(Instant publishedBefore, int limit) {
        return delete(PRUNE_OUTBOX, publishedBefore, limit, "published outbox rows");
    }

    @Override
    public int pruneReservations(Instant expiredBefore, int limit) {
        return delete(PRUNE_RESERVATIONS, expiredBefore, limit, "finished reservations");
    }

    /** The contract {@link Retention} states: zero means "delete nothing", never "no limit". */
    private static void requireBatch(int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be at least 1, got " + limit);
        }
    }

    private int delete(String sql, Instant before, int limit, String what) {
        requireBatch(limit);
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, offset(before));
            statement.setInt(2, limit);
            return statement.executeUpdate();
        } catch (SQLException e) {
            throw new LedgerException("deleting " + what + " older than " + before, e);
        }
    }

    @Override
    public Optional<StockItem> stock(Sku sku) {
        try (Connection connection = dataSource.getConnection()) {
            List<StockShard> shards = readStock(connection, Set.of(sku)).get(sku);
            return shards == null ? Optional.empty() : Optional.of(StockItem.of(sku, shards));
        } catch (SQLException e) {
            throw new LedgerException("reading stock for " + sku, e);
        }
    }

    @Override
    public Optional<Reservation> reservation(ReservationId id) {
        try (Connection connection = dataSource.getConnection()) {
            return readReservation(connection, id);
        } catch (SQLException e) {
            throw new LedgerException("reading reservation " + id, e);
        }
    }

    @Override
    public List<StockItem> listStock(Optional<Sku> after, int limit) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(LIST_STOCK)) {
            statement.setString(1, after.map(Sku::value).orElse(null));
            statement.setInt(2, page(limit));
            try (ResultSet rows = statement.executeQuery()) {
                return readStockRows(rows);
            }
        } catch (SQLException e) {
            throw new LedgerException("listing stock", e);
        }
    }

    @Override
    public List<Reservation> listReservations(Optional<ReservationState> state, int limit) {
        try (Connection connection = dataSource.getConnection()) {
            List<Row> rows = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(LIST_RESERVATIONS)) {
                statement.setString(1, state.map(Enum::name).orElse(null));
                statement.setInt(2, page(limit));
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        rows.add(readRow(result));
                    }
                }
            }
            return withLines(connection, rows);
        } catch (SQLException e) {
            throw new LedgerException("listing reservations", e);
        }
    }

    /** Clamped here rather than trusted from the caller, because the caller is an HTTP parameter. */
    private static int page(int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be at least 1, got " + limit);
        }
        return Math.min(limit, LedgerInspector.MAX_PAGE);
    }

    @Override
    public List<StockItem> allStock() {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(ALL_STOCK);
                ResultSet rows = statement.executeQuery()) {
            return readStockRows(rows);
        } catch (SQLException e) {
            throw new LedgerException("scanning stock", e);
        }
    }

    @Override
    public List<StockShard> allShards() {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(ALL_SHARDS);
                ResultSet rows = statement.executeQuery()) {
            List<StockShard> shards = new ArrayList<>();
            while (rows.next()) {
                shards.add(readShard(rows));
            }
            return shards;
        } catch (SQLException e) {
            throw new LedgerException("scanning stock shards", e);
        }
    }

    @Override
    public List<Reservation> allReservations() {
        try (Connection connection = dataSource.getConnection()) {
            List<Row> rows = new ArrayList<>();
            try (PreparedStatement statement =
                            connection.prepareStatement(
                                    "select id, idem_key, state, created_at, expires_at, version "
                                            + "from till_reservation order by id collate \"C\"");
                    ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    rows.add(readRow(result));
                }
            }
            return withLines(connection, rows);
        } catch (SQLException e) {
            throw new LedgerException("listing reservations", e);
        }
    }

    @Override
    public List<OutboxEntry> allEvents() {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "select sequence, payload, recorded_at from till_outbox order by sequence");
                ResultSet rows = statement.executeQuery()) {
            List<OutboxEntry> entries = new ArrayList<>();
            while (rows.next()) {
                entries.add(
                        new OutboxEntry(
                                rows.getLong("sequence"),
                                Codec.decodeEvent(rows.getString("payload")),
                                instant(rows, "recorded_at")));
            }
            return entries;
        } catch (SQLException e) {
            throw new LedgerException("listing outbox entries", e);
        }
    }

    /**
     * Attaches the lines to a page of reservation headers.
     *
     * <p>One query for the lines of the whole page rather than one per reservation: a listing of a
     * hundred holds would otherwise be a hundred and one round trips.
     */
    private List<Reservation> withLines(Connection connection, List<Row> rows) throws SQLException {
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<ReservationId, List<Allocation>> lines =
                readLines(connection, rows.stream().map(row -> ReservationId.of(row.id)).toList());
        List<Reservation> reservations = new ArrayList<>(rows.size());
        for (Row row : rows) {
            reservations.add(row.toReservation(requireLines(lines, ReservationId.of(row.id))));
        }
        return reservations;
    }

    /** Levels, from {@link #LEVELS}: one row per SKU, its shards added up. */
    private static List<StockItem> readStockRows(ResultSet rows) throws SQLException {
        List<StockItem> items = new ArrayList<>();
        while (rows.next()) {
            items.add(
                    new StockItem(
                            Sku.of(rows.getString("sku")),
                            rows.getLong("on_hand"),
                            rows.getLong("reserved"),
                            rows.getLong("version"),
                            rows.getInt("shards")));
        }
        return items;
    }

    private static StockShard readShard(ResultSet rows) throws SQLException {
        return new StockShard(
                Sku.of(rows.getString("sku")),
                rows.getInt("shard"),
                rows.getLong("on_hand"),
                rows.getLong("reserved"),
                rows.getLong("version"));
    }

    private static Array skuArray(Connection connection, Collection<Sku> skus) throws SQLException {
        return connection.createArrayOf("varchar", skus.stream().map(Sku::value).toArray());
    }

    private static Array idArray(Connection connection, List<ReservationId> ids) throws SQLException {
        return connection.createArrayOf("varchar", ids.stream().map(ReservationId::value).toArray());
    }

    private static OffsetDateTime offset(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static Instant instant(ResultSet rows, String column) throws SQLException {
        return rows.getObject(column, OffsetDateTime.class).toInstant();
    }

    private static Row readRow(ResultSet rows) throws SQLException {
        return new Row(
                rows.getString("id"),
                rows.getString("idem_key"),
                rows.getString("state"),
                instant(rows, "created_at"),
                instant(rows, "expires_at"),
                rows.getLong("version"));
    }

    /**
     * Refuses a decision that lists its mutations in an order other than the kernel's — stock rows,
     * then state changes, then new reservations — which is the order {@code till_apply} writes them in.
     * A decision applied in any other order would lock its rows in an order nobody chose.
     *
     * @throws IllegalArgumentException if a mutation follows one of a kind the function writes later
     */
    private static void requireKernelOrder(Decision decision) {
        int reached = 0;
        for (Mutation mutation : decision.mutations()) {
            int kind =
                    switch (mutation) {
                        case Mutation.PutStock ignored -> 0;
                        case Mutation.SetReservationState ignored -> 1;
                        case Mutation.InsertReservation ignored -> 2;
                    };
            if (kind < reached) {
                throw new IllegalArgumentException(
                        "a decision lists its stock rows, then its state changes, then its new reservations, "
                                + "which is the order till_apply writes them in; " + mutation
                                + " comes too late in " + decision.mutations());
            }
            reached = kind;
        }
    }

    /** A row of {@code till_reservation_line} for a reservation being inserted. */
    private record LineRow(ReservationId reservation, Allocation allocation) {}

    /** A reservation header, before its lines have been fetched. */
    private record Row(String id, String key, String state, Instant createdAt, Instant expiresAt, long version) {

        /** The header with its allocations, and the lines they add up to. */
        private Reservation toReservation(List<Allocation> allocations) {
            Map<Sku, Long> perSku = new LinkedHashMap<>();
            allocations.forEach(allocation -> perSku.merge(allocation.sku(), allocation.quantity(), Math::addExact));
            List<Line> lines = new ArrayList<>();
            perSku.forEach((sku, quantity) -> lines.add(new Line(sku, quantity)));
            return new Reservation(
                    ReservationId.of(id),
                    IdempotencyKey.of(key),
                    lines,
                    allocations,
                    ReservationState.valueOf(state),
                    createdAt,
                    expiresAt,
                    version);
        }
    }
}
