package io.till.jdbc;

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
import io.till.core.OutcomeRecord;
import io.till.core.Reservation;
import io.till.core.ReservationId;
import io.till.core.ReservationState;
import io.till.core.Sku;
import io.till.core.Snapshot;
import io.till.core.StockItem;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.sql.DataSource;

/**
 * The {@link Ledger} on PostgreSQL.
 *
 * <p>Two transactions per attempt, on purpose, and the reason is the whole design:
 *
 * <ul>
 *   <li><b>Reading</b> happens in a read-only repeatable-read transaction. The kernel's contract is
 *       that a snapshot is one instant, and at read committed the four statements it takes to
 *       assemble one would each see a different instant — each row correct, the set of them
 *       describing a state that never existed.
 *   <li><b>Writing</b> happens at read committed, with every statement carrying the version it
 *       expects. Nothing is locked between the two transactions, so a caller that thinks for a
 *       second blocks nobody; if the row moved underneath it, its update matches no row, the whole
 *       transaction is rolled back, and {@code apply} returns {@code false} so that the caller can
 *       decide again against what is there now.
 * </ul>
 *
 * <p><b>Refused is not failed.</b> A version that has moved, a taken reservation id, an idempotency
 * key claimed by a concurrent copy of the same request: all of them return {@code false}, which is
 * the ordinary outcome of contention. A check constraint violation is <i>not</i> treated that way and
 * raises {@link LedgerException} instead, because that means the application tried to write a level
 * the database knows is impossible, and retrying it would loop forever around a real bug.
 *
 * <p>Conflicts are detected with {@code on conflict do nothing} and a row count rather than by
 * catching a unique violation. A failed statement inside a PostgreSQL transaction aborts the whole
 * transaction, so the exception route makes every conflict cost a rollback of work already done and
 * makes the code read as though an exception were the expected case.
 *
 * <p>Instances hold nothing but the {@link DataSource} and are safe to share between threads.
 *
 * <p>Requires the schema in {@code db/migration/V1__till_schema.sql}; see {@link JdbcSchema}.
 */
public final class JdbcLedger implements Ledger, LedgerInspector, Outbox {

    private static final String SELECT_RECORD =
            "select idem_key, fingerprint, outcome, recorded_at from till_idempotency where idem_key = ?";

    private static final String SELECT_RESERVATION =
            "select id, idem_key, state, created_at, expires_at, version from till_reservation where id = ?";

    private static final String SELECT_LINES =
            "select reservation_id, sku, quantity from till_reservation_line "
                    + "where reservation_id = any(?) order by reservation_id, sku";

    private static final String SELECT_STOCK =
            "select sku, on_hand, reserved, version from till_stock where sku = any(?) "
                    + "order by sku collate \"C\"";

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

    private static final String INSERT_STOCK =
            "insert into till_stock (sku, on_hand, reserved, version) values (?, ?, ?, 0) "
                    + "on conflict (sku) do nothing";

    private static final String UPDATE_STOCK =
            "update till_stock set on_hand = ?, reserved = ?, version = version + 1, updated_at = now() "
                    + "where sku = ? and version = ?";

    private static final String INSERT_RESERVATION =
            "insert into till_reservation (id, idem_key, state, created_at, expires_at, version) "
                    + "values (?, ?, ?, ?, ?, 0) on conflict (id) do nothing";

    private static final String INSERT_LINE =
            "insert into till_reservation_line (reservation_id, sku, quantity) values (?, ?, ?)";

    private static final String UPDATE_RESERVATION =
            "update till_reservation set state = ?, version = version + 1 where id = ? and version = ?";

    private static final String INSERT_RECORD =
            "insert into till_idempotency (idem_key, fingerprint, outcome, recorded_at) values (?, ?, ?, ?) "
                    + "on conflict (idem_key) do nothing";

    private static final String INSERT_OUTBOX =
            "insert into till_outbox (dedupe_key, payload, recorded_at) values (?, ?, ?) "
                    + "on conflict (dedupe_key) do nothing";

    private static final String SELECT_UNPUBLISHED =
            "select sequence, payload, recorded_at from till_outbox where published_at is null "
                    + "order by sequence limit ?";

    private static final String MARK_PUBLISHED =
            "update till_outbox set published_at = now() where sequence = any(?) and published_at is null";

    private static final String LIST_STOCK =
            "select sku, on_hand, reserved, version from till_stock "
                    + "where sku collate \"C\" > coalesce(?, '') order by sku collate \"C\" limit ?";

    /**
     * Newest first, with the id breaking ties so that two holds created in the same microsecond come
     * back in a fixed order. The state filter is a parameter rather than two statements because
     * {@code state = coalesce(?, state)} lets PostgreSQL plan one query for both shapes.
     */
    private static final String LIST_RESERVATIONS =
            "select id, idem_key, state, created_at, expires_at, version from till_reservation "
                    + "where state = coalesce(?, state) order by created_at desc, id limit ?";

    /** PostgreSQL's SQLState for a check constraint violation, which is a bug and not contention. */
    private static final String CHECK_VIOLATION = "23514";

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

    @Override
    public Snapshot load(Command command, Instant now, int reclaimLimit) {
        try (Connection connection = dataSource.getConnection()) {
            boolean autoCommit = connection.getAutoCommit();
            int isolation = connection.getTransactionIsolation();
            connection.setAutoCommit(false);
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            connection.setReadOnly(true);
            try {
                Snapshot snapshot = readSnapshot(connection, command, now, reclaimLimit);
                connection.commit();
                return snapshot;
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setReadOnly(false);
                connection.setTransactionIsolation(isolation);
                connection.setAutoCommit(autoCommit);
            }
        } catch (SQLException e) {
            throw new LedgerException("loading a snapshot for " + command, e);
        }
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

        Map<Sku, StockItem> levels = readStock(connection, scope);
        for (Sku sku : scope) {
            StockItem item = levels.get(sku);
            builder.stock(item != null ? item : StockItem.empty(sku));
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
        Map<ReservationId, List<Line>> lines = readLines(connection, List.of(id));
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
        Map<ReservationId, List<Line>> lines =
                readLines(connection, rows.stream().map(row -> ReservationId.of(row.id)).toList());
        List<Reservation> reservations = new ArrayList<>(rows.size());
        for (Row row : rows) {
            reservations.add(row.toReservation(requireLines(lines, ReservationId.of(row.id))));
        }
        return reservations;
    }

    private Map<ReservationId, List<Line>> readLines(Connection connection, List<ReservationId> ids)
            throws SQLException {
        Map<ReservationId, List<Line>> lines = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(SELECT_LINES)) {
            statement.setArray(1, idArray(connection, ids));
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    lines.computeIfAbsent(ReservationId.of(rows.getString("reservation_id")), ignored -> new ArrayList<>())
                            .add(new Line(Sku.of(rows.getString("sku")), rows.getLong("quantity")));
                }
            }
        }
        return lines;
    }

    private static List<Line> requireLines(Map<ReservationId, List<Line>> lines, ReservationId id) {
        List<Line> found = lines.get(id);
        if (found == null || found.isEmpty()) {
            // The foreign key makes orphaned lines impossible; a reservation with none means
            // somebody wrote the header without them, which is not a state to paper over.
            throw new IllegalStateException("reservation " + id + " has no lines");
        }
        return found;
    }

    private Map<Sku, StockItem> readStock(Connection connection, Set<Sku> skus) throws SQLException {
        Map<Sku, StockItem> levels = new LinkedHashMap<>();
        if (skus.isEmpty()) {
            return levels;
        }
        try (PreparedStatement statement = connection.prepareStatement(SELECT_STOCK)) {
            statement.setArray(1, skuArray(connection, skus));
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    Sku sku = Sku.of(rows.getString("sku"));
                    levels.put(
                            sku,
                            new StockItem(
                                    sku, rows.getLong("on_hand"), rows.getLong("reserved"), rows.getLong("version")));
                }
            }
        }
        return levels;
    }

    @Override
    public boolean apply(Decision decision) {
        try (Connection connection = dataSource.getConnection()) {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                boolean written = write(connection, decision);
                if (written) {
                    connection.commit();
                } else {
                    connection.rollback();
                }
                return written;
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(autoCommit);
            }
        } catch (SQLException e) {
            if (CHECK_VIOLATION.equals(e.getSQLState())) {
                // The database refused a level the kernel should never have produced. Retrying
                // would spin around the bug rather than report it.
                throw new LedgerException(
                        "the database refused an impossible stock level, which is a bug rather than contention", e);
            }
            throw new LedgerException("applying " + decision.outcome(), e);
        }
    }

    private boolean write(Connection connection, Decision decision) throws SQLException {
        for (Mutation mutation : decision.mutations()) {
            boolean ok =
                    switch (mutation) {
                        case Mutation.PutStock m -> putStock(connection, m);
                        case Mutation.InsertReservation m -> insertReservation(connection, m.reservation());
                        case Mutation.SetReservationState m -> setState(connection, m);
                    };
            if (!ok) {
                return false;
            }
        }
        for (Event event : decision.events()) {
            if (!insertOutbox(connection, event)) {
                return false;
            }
        }
        Optional<OutcomeRecord> record = decision.outcomeRecord();
        return record.isEmpty() || insertRecord(connection, record.get());
    }

    private boolean putStock(Connection connection, Mutation.PutStock mutation) throws SQLException {
        if (mutation.isInsert()) {
            try (PreparedStatement statement = connection.prepareStatement(INSERT_STOCK)) {
                statement.setString(1, mutation.sku().value());
                statement.setLong(2, mutation.onHand());
                statement.setLong(3, mutation.reserved());
                return statement.executeUpdate() == 1;
            }
        }
        try (PreparedStatement statement = connection.prepareStatement(UPDATE_STOCK)) {
            statement.setLong(1, mutation.onHand());
            statement.setLong(2, mutation.reserved());
            statement.setString(3, mutation.sku().value());
            statement.setLong(4, mutation.expectedVersion());
            return statement.executeUpdate() == 1;
        }
    }

    private boolean insertReservation(Connection connection, Reservation reservation) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(INSERT_RESERVATION)) {
            statement.setString(1, reservation.id().value());
            statement.setString(2, reservation.key().value());
            statement.setString(3, reservation.state().name());
            statement.setObject(4, offset(reservation.createdAt()));
            statement.setObject(5, offset(reservation.expiresAt()));
            if (statement.executeUpdate() != 1) {
                return false;
            }
        }
        try (PreparedStatement statement = connection.prepareStatement(INSERT_LINE)) {
            for (Line line : reservation.lines()) {
                statement.setString(1, reservation.id().value());
                statement.setString(2, line.sku().value());
                statement.setLong(3, line.quantity());
                statement.addBatch();
            }
            statement.executeBatch();
        }
        return true;
    }

    private boolean setState(Connection connection, Mutation.SetReservationState mutation) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(UPDATE_RESERVATION)) {
            statement.setString(1, mutation.state().name());
            statement.setString(2, mutation.reservationId().value());
            statement.setLong(3, mutation.expectedVersion());
            return statement.executeUpdate() == 1;
        }
    }

    private boolean insertOutbox(Connection connection, Event event) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(INSERT_OUTBOX)) {
            statement.setString(1, event.dedupeKey());
            statement.setString(2, Codec.encodeEvent(event));
            statement.setObject(3, offset(event.occurredAt()));
            return statement.executeUpdate() == 1;
        }
    }

    private boolean insertRecord(Connection connection, OutcomeRecord record) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(INSERT_RECORD)) {
            statement.setString(1, record.key().value());
            statement.setString(2, record.fingerprint());
            statement.setString(3, record.encodedOutcome());
            statement.setObject(4, offset(record.recordedAt()));
            return statement.executeUpdate() == 1;
        }
    }

    @Override
    public List<OutboxEntry> unpublished(int limit) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(SELECT_UNPUBLISHED)) {
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
        } catch (SQLException e) {
            throw new LedgerException("reading the outbox", e);
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
    public void markPublished(List<Long> sequences) {
        if (sequences.isEmpty()) {
            return;
        }
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(MARK_PUBLISHED)) {
            statement.setArray(1, connection.createArrayOf("bigint", sequences.toArray()));
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new LedgerException("marking outbox rows published", e);
        }
    }

    @Override
    public Optional<StockItem> stock(Sku sku) {
        try (Connection connection = dataSource.getConnection()) {
            return Optional.ofNullable(readStock(connection, Set.of(sku)).get(sku));
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
                PreparedStatement statement =
                        connection.prepareStatement(
                                "select sku, on_hand, reserved, version from till_stock order by sku collate \"C\"");
                ResultSet rows = statement.executeQuery()) {
            return readStockRows(rows);
        } catch (SQLException e) {
            throw new LedgerException("scanning stock", e);
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
        Map<ReservationId, List<Line>> lines =
                readLines(connection, rows.stream().map(row -> ReservationId.of(row.id)).toList());
        List<Reservation> reservations = new ArrayList<>(rows.size());
        for (Row row : rows) {
            reservations.add(row.toReservation(requireLines(lines, ReservationId.of(row.id))));
        }
        return reservations;
    }

    private static List<StockItem> readStockRows(ResultSet rows) throws SQLException {
        List<StockItem> items = new ArrayList<>();
        while (rows.next()) {
            items.add(
                    new StockItem(
                            Sku.of(rows.getString("sku")),
                            rows.getLong("on_hand"),
                            rows.getLong("reserved"),
                            rows.getLong("version")));
        }
        return items;
    }

    private static Array skuArray(Connection connection, Set<Sku> skus) throws SQLException {
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

    /** A reservation header, before its lines have been fetched. */
    private record Row(String id, String key, String state, Instant createdAt, Instant expiresAt, long version) {

        private Reservation toReservation(List<Line> lines) {
            return new Reservation(
                    ReservationId.of(id),
                    IdempotencyKey.of(key),
                    lines,
                    ReservationState.valueOf(state),
                    createdAt,
                    expiresAt,
                    version);
        }
    }
}
