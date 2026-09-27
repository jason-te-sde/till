package io.till.store.orders;

import io.till.core.ReservationId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Where orders are kept.
 *
 * <p>Every read that serves a customer takes the customer as a parameter and filters by it in SQL.
 * There is no "find by id" a controller could call and then forget to check ownership on: the only
 * unscoped lookup is by reservation, and only the event projection uses that.
 */
@Repository
public class Orders {

    private static final String ORDER_COLUMNS =
            "id, customer, idem_key, reservation_id, status, total_cents, currency, created_at, expires_at, closed_at";

    private final JdbcClient jdbc;

    Orders(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Records an order, unless this customer already placed one with this key.
     *
     * <p>{@code on conflict do nothing} against the unique (customer, key) pair is the concurrency
     * control. Two identical requests racing each other both reach this line, one row is written, and
     * both callers then read the same order back — without a lock, and without a window in which each
     * sees nothing and inserts.
     *
     * @param order the order
     * @return whether this call wrote it
     */
    @Transactional
    public boolean insertIfAbsent(Order order) {
        int inserted = jdbc.sql("insert into store_order (" + ORDER_COLUMNS + ") values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
                        + " on conflict (customer, idem_key) do nothing")
                .params(
                        order.id(),
                        order.customer(),
                        order.idemKey(),
                        order.reservationId().value(),
                        order.status().name(),
                        order.totalCents(),
                        order.currency(),
                        utc(order.createdAt()),
                        utc(order.expiresAt()),
                        order.closedAt() == null ? null : utc(order.closedAt()))
                .update();
        if (inserted == 0) {
            return false;
        }
        for (Order.Line line : order.lines()) {
            jdbc.sql("insert into store_order_line (order_id, sku, title, unit_price_cents, quantity) values (?, ?, ?, ?, ?)")
                    .params(order.id(), line.sku(), line.title(), line.unitPriceCents(), line.quantity())
                    .update();
        }
        return true;
    }

    /**
     * @param customer who is asking
     * @param id the order
     * @return the order, if it exists <i>and is theirs</i>
     */
    public Optional<Order> find(String customer, UUID id) {
        return first(jdbc.sql("select " + ORDER_COLUMNS + " from store_order where id = ? and customer = ?")
                .params(id, customer)
                .query(Orders::readHeader)
                .list());
    }

    /**
     * @param customer who is asking
     * @param idemKey the key a checkout attempt was placed under
     * @return the order that attempt placed, if any
     */
    public Optional<Order> findByKey(String customer, String idemKey) {
        return first(jdbc.sql("select " + ORDER_COLUMNS + " from store_order where customer = ? and idem_key = ?")
                .params(customer, idemKey)
                .query(Orders::readHeader)
                .list());
    }

    /**
     * @param customer who is asking
     * @param limit at most this many
     * @return their orders, newest first
     */
    public List<Order> forCustomer(String customer, int limit) {
        return withLines(jdbc.sql("select " + ORDER_COLUMNS + " from store_order where customer = ?"
                        + " order by created_at desc, id limit ?")
                .params(customer, limit)
                .query(Orders::readHeader)
                .list());
    }

    /**
     * Moves an order out of {@link Order.Status#PENDING}, if it is still there.
     *
     * <p>Guarded by the current status, so transitions only ever go one way. An expiry event that
     * arrives after the order was paid finds nothing to update, and a paid order can never be made
     * expired by a message that was merely late.
     *
     * @param id the order
     * @param to the status to move to
     * @param at when it happened
     * @return whether it moved
     */
    public boolean close(UUID id, Order.Status to, Instant at) {
        return jdbc.sql("update store_order set status = ?, closed_at = ? where id = ? and status = 'PENDING'")
                .params(to.name(), utc(at), id)
                .update() == 1;
    }

    /**
     * The same move, found by the ledger's reservation rather than the store's id.
     *
     * <p>For the event projection, which knows reservations and not orders.
     *
     * @param reservationId the hold
     * @param to the status to move to
     * @param at when it happened, by the ledger's clock
     * @return whether an order moved
     */
    public boolean closeByReservation(ReservationId reservationId, Order.Status to, Instant at) {
        return jdbc.sql("update store_order set status = ?, closed_at = ? where reservation_id = ? and status = 'PENDING'")
                .params(to.name(), utc(at), reservationId.value())
                .update() == 1;
    }

    private Optional<Order> first(List<Order> headers) {
        return withLines(headers).stream().findFirst();
    }

    /** One query for every order's lines, rather than one per order. */
    private List<Order> withLines(List<Order> headers) {
        if (headers.isEmpty()) {
            return headers;
        }
        Map<UUID, List<Order.Line>> lines = new LinkedHashMap<>();
        headers.forEach(order -> lines.put(order.id(), new ArrayList<>()));
        jdbc.sql("select order_id, sku, title, unit_price_cents, quantity from store_order_line"
                        + " where order_id in (:ids) order by order_id, sku")
                .param("ids", lines.keySet())
                .query((rs, row) -> {
                    lines.get(rs.getObject("order_id", UUID.class)).add(new Order.Line(
                            rs.getString("sku"), rs.getString("title"), rs.getLong("unit_price_cents"), rs.getInt("quantity")));
                    return null;
                })
                .list();
        return headers.stream()
                .map(order -> new Order(order.id(), order.customer(), order.idemKey(), order.reservationId(), order.status(),
                        order.totalCents(), order.currency(), order.createdAt(), order.expiresAt(), order.closedAt(),
                        lines.get(order.id())))
                .toList();
    }

    private static Order readHeader(ResultSet rs, int row) throws SQLException {
        OffsetDateTime closed = rs.getObject("closed_at", OffsetDateTime.class);
        return new Order(
                rs.getObject("id", UUID.class),
                rs.getString("customer"),
                rs.getString("idem_key"),
                ReservationId.of(rs.getString("reservation_id")),
                Order.Status.valueOf(rs.getString("status")),
                rs.getLong("total_cents"),
                rs.getString("currency"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getObject("expires_at", OffsetDateTime.class).toInstant(),
                closed == null ? null : closed.toInstant(),
                List.of());
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
