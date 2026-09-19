package io.till.catalogue;

import io.till.core.Event;
import io.till.core.Line;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * What the storefront believes is still buyable, maintained from till's events.
 *
 * <h2>Why this is allowed to be wrong</h2>
 *
 * <p>This table is a <b>cache with a timestamp on it</b>, not an authority. It lags the ledger by
 * however long the event took to arrive, and a storefront that showed "3 left" a second after the
 * third was sold has not malfunctioned. The only place that decides whether a sale is allowed is
 * till, over HTTP, at the moment of the reservation — which is why {@code available} being stale
 * costs a customer one refused checkout and never an oversell.
 *
 * <h2>Why the inbox exists</h2>
 *
 * <p>Delivery is at least once. That is not a rare failure: a publisher that dies between a
 * successful send and the database write that records it repeats the send on restart, by design.
 * And the reservation events carry <b>deltas</b> — a list of lines — so applying one twice moves
 * {@code reserved} twice and the projection is quietly wrong forever after.
 *
 * <p>So every event is inserted into {@code catalogue_consumed_event} by its deduplication key
 * <b>in the same transaction</b> as the numbers it moves. A redelivery hits the primary key, the
 * insert conflicts, and the whole transaction is abandoned with the numbers untouched. Producer-side
 * outbox, consumer-side inbox: an outbox without one is half a design, and the half that is missing
 * is the half that keeps the reader correct.
 *
 * <p>The ordering guarantee this relies on is per entity, which is what the publisher's partition
 * key buys: one reservation's reserve-then-commit cannot arrive backwards. Two different SKUs can
 * interleave freely, and nothing here cares.
 */
@Component
class AvailabilityProjection {

    private static final Logger LOG = LoggerFactory.getLogger(AvailabilityProjection.class);

    private static final String CLAIM =
            "insert into catalogue_consumed_event (dedupe_key, sequence, consumed_at) values (?, ?, ?) "
                    + "on conflict (dedupe_key) do nothing";

    /**
     * Upsert, because a SKU's first event may be a reserve rather than an adjustment — till does not
     * promise this service saw the stock arrive. The deltas are applied to whatever is there.
     */
    private static final String APPLY_DELTA =
            "insert into catalogue_availability (sku, on_hand, reserved, available, updated_at) "
                    + "values (?, ?, ?, ? - ?, ?) "
                    + "on conflict (sku) do update set "
                    + "  on_hand = catalogue_availability.on_hand + excluded.on_hand, "
                    + "  reserved = catalogue_availability.reserved + excluded.reserved, "
                    + "  available = catalogue_availability.on_hand + excluded.on_hand "
                    + "            - (catalogue_availability.reserved + excluded.reserved), "
                    + "  updated_at = excluded.updated_at";

    /**
     * An adjustment carries the level <i>afterwards</i>, not a delta, so it is the one event that can
     * repair a projection that has drifted. Written as a set rather than an add for exactly that
     * reason.
     */
    private static final String APPLY_ABSOLUTE =
            "insert into catalogue_availability (sku, on_hand, reserved, available, updated_at) "
                    + "values (?, ?, ?, ?, ?) "
                    + "on conflict (sku) do update set "
                    + "  on_hand = excluded.on_hand, "
                    + "  reserved = excluded.reserved, "
                    + "  available = excluded.available, "
                    + "  updated_at = excluded.updated_at";

    private static final String SELECT_ONE =
            "select sku, on_hand, reserved, available, updated_at from catalogue_availability where sku = ?";

    private static final String SELECT_MANY =
            "select sku, on_hand, reserved, available, updated_at from catalogue_availability "
                    + "where sku = any(?)";

    private final DataSource dataSource;

    AvailabilityProjection(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * Applies one event, once.
     *
     * @param dedupeKey the key the producer stamped on the record
     * @param sequence the outbox sequence, kept for diagnosis rather than for ordering
     * @param event what happened
     * @return whether this call moved anything; false means it was a redelivery
     */
    boolean apply(String dedupeKey, long sequence, Event event) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                if (!claim(connection, dedupeKey, sequence)) {
                    // Seen before. Rolled back rather than committed, so that a partially applied
                    // transaction from a previous crash cannot be "confirmed" by this one.
                    connection.rollback();
                    LOG.debug("skipping {}, already consumed", dedupeKey);
                    return false;
                }
                applyTo(connection, event);
                connection.commit();
                return true;
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new CatalogueException("applying " + dedupeKey + " to the availability projection", e);
        }
    }

    private static boolean claim(Connection connection, String dedupeKey, long sequence) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(CLAIM)) {
            statement.setString(1, dedupeKey);
            statement.setLong(2, sequence);
            statement.setObject(3, OffsetDateTime.now(ZoneOffset.UTC));
            return statement.executeUpdate() == 1;
        }
    }

    private static void applyTo(Connection connection, Event event) throws SQLException {
        switch (event) {
            // Stock set aside: on-hand is unchanged, reserved goes up, so available goes down.
            case Event.StockReserved e -> delta(connection, e.lines(), 0, +1, e.occurredAt());
            // Sold: both go down by the same amount, so available is unchanged — which is right, the
            // units stopped being available when they were reserved, not when they were paid for.
            case Event.StockCommitted e -> delta(connection, e.lines(), -1, -1, e.occurredAt());
            // Given back, on purpose or by running out of time: reserved falls, available rises.
            case Event.StockReleased e -> delta(connection, e.lines(), 0, -1, e.occurredAt());
            case Event.StockExpired e -> delta(connection, e.lines(), 0, -1, e.occurredAt());
            // The only event carrying absolute levels, and so the only one that can repair drift.
            case Event.StockAdjusted e -> absolute(connection, e);
        }
    }

    private static void delta(Connection connection, List<Line> lines, int onHandSign, int reservedSign, Instant at)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(APPLY_DELTA)) {
            for (Line line : lines) {
                long onHand = onHandSign * line.quantity();
                long reserved = reservedSign * line.quantity();
                statement.setString(1, line.sku().value());
                statement.setLong(2, onHand);
                statement.setLong(3, reserved);
                statement.setLong(4, onHand);
                statement.setLong(5, reserved);
                statement.setObject(6, OffsetDateTime.ofInstant(at, ZoneOffset.UTC));
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private static void absolute(Connection connection, Event.StockAdjusted event) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(APPLY_ABSOLUTE)) {
            statement.setString(1, event.sku().value());
            statement.setLong(2, event.onHand());
            statement.setLong(3, event.reserved());
            statement.setLong(4, event.onHand() - event.reserved());
            statement.setObject(5, OffsetDateTime.ofInstant(event.occurredAt(), ZoneOffset.UTC));
            statement.executeUpdate();
        }
    }

    /**
     * @param sku the SKU to read
     * @return what the storefront currently believes, or empty if it has heard nothing about it
     */
    Optional<Availability> of(String sku) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(SELECT_ONE)) {
            statement.setString(1, sku);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(read(rows)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new CatalogueException("reading availability for " + sku, e);
        }
    }

    /**
     * @param skus the SKUs to read
     * @return what is known about them; a SKU nothing is known about is simply absent
     */
    List<Availability> of(List<String> skus) {
        if (skus.isEmpty()) {
            return List.of();
        }
        // One statement for the page, rather than one per row. A storefront listing eight games
        // should make one query, and the n+1 version of this is the classic way it stops doing so.
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(SELECT_MANY)) {
            statement.setArray(1, connection.createArrayOf("varchar", skus.toArray()));
            try (ResultSet rows = statement.executeQuery()) {
                List<Availability> found = new ArrayList<>();
                while (rows.next()) {
                    found.add(read(rows));
                }
                return found;
            }
        } catch (SQLException e) {
            throw new CatalogueException("reading availability for " + skus.size() + " SKUs", e);
        }
    }

    private static Availability read(ResultSet rows) throws SQLException {
        return new Availability(
                rows.getString("sku"),
                rows.getLong("on_hand"),
                rows.getLong("reserved"),
                rows.getLong("available"),
                rows.getObject("updated_at", OffsetDateTime.class).toInstant());
    }

    /**
     * What the storefront believes about one SKU, and when it last heard.
     *
     * @param sku the SKU
     * @param onHand units the ledger said it had
     * @param reserved units spoken for
     * @param available what a new hold could have taken, as of {@code updatedAt}
     * @param updatedAt the decision instant of the last event applied — till's clock, not this
     *     service's, so the age of this number is the age of the information rather than of the row
     */
    record Availability(String sku, long onHand, long reserved, long available, Instant updatedAt) {}
}
