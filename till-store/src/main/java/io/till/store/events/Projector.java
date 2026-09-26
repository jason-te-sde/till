package io.till.store.events;

import io.till.core.Event;
import io.till.core.Line;
import io.till.store.orders.Order;
import io.till.store.orders.Orders;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies the ledger's events to everything the store derives from them — once.
 *
 * <h2>Three read models, one transaction</h2>
 *
 * <p>Each event moves up to three things: <b>availability</b> (what a store page says is left),
 * <b>sales</b> (the best-seller roll-up) and <b>orders</b> (a paid, cancelled or expired hold moves the
 * order it belongs to). All three are written in one transaction with the inbox claim, so an event is
 * either wholly applied or not at all — never counted as a sale but left as a pending order.
 *
 * <h2>Why the inbox exists</h2>
 *
 * <p>Delivery is at least once. That is not a rare failure: a publisher that dies between a successful
 * send and the write recording it repeats the send on restart, by design. And the reservation events
 * carry <b>deltas</b> — a list of lines — so applying one twice moves {@code reserved} twice, counts a
 * sale twice, and leaves the numbers quietly wrong from then on.
 *
 * <p>So the event's deduplication key is claimed first, in the same transaction. A redelivery finds the
 * key already there, and nothing else is touched. Producer-side outbox, consumer-side inbox: an outbox
 * without an inbox is half a design, and the missing half is the half that keeps the reader correct.
 *
 * <h2>Why order transitions are safe to replay out of step</h2>
 *
 * <p>Every order update is guarded by {@code status = 'PENDING'}. An expiry that arrives after the
 * order was paid through the HTTP path finds nothing to change, so a late or duplicated event can
 * never walk an order backwards.
 */
@Component
public class Projector {

    private final JdbcClient jdbc;
    private final Orders orders;
    private final Clock clock;

    Projector(JdbcClient jdbc, Orders orders, Clock clock) {
        this.jdbc = jdbc;
        this.orders = orders;
        this.clock = clock;
    }

    /**
     * Applies one event, once.
     *
     * @param dedupeKey the key the producer stamped on the record
     * @param sequence the ledger's outbox sequence, kept for diagnosis rather than for ordering
     * @param event what happened
     * @return whether this call changed anything; false means it was a redelivery
     */
    @Transactional
    public boolean apply(String dedupeKey, long sequence, Event event) {
        if (!claim(dedupeKey, sequence)) {
            return false;
        }
        switch (event) {
            // Stock set aside: on-hand unchanged, reserved up, so available down.
            case Event.StockReserved e -> delta(e.lines(), 0, +1, e.occurredAt());
            // Sold: both fall together, so available does not move — the units stopped being
            // available when they were held, not when they were paid for. A projection that dropped
            // available again here would count every sale twice.
            case Event.StockCommitted e -> {
                delta(e.lines(), -1, -1, e.occurredAt());
                sold(e.lines(), e.occurredAt());
                orders.closeByReservation(e.reservationId(), Order.Status.PAID, e.occurredAt());
            }
            case Event.StockReleased e -> {
                delta(e.lines(), 0, -1, e.occurredAt());
                orders.closeByReservation(e.reservationId(), Order.Status.CANCELLED, e.occurredAt());
            }
            case Event.StockExpired e -> {
                delta(e.lines(), 0, -1, e.occurredAt());
                orders.closeByReservation(e.reservationId(), Order.Status.EXPIRED, e.occurredAt());
            }
            // The one event carrying absolute levels, and so the one that can repair drift.
            case Event.StockAdjusted e -> absolute(e);
        }
        return true;
    }

    private boolean claim(String dedupeKey, long sequence) {
        return jdbc.sql("insert into store_consumed_event (dedupe_key, sequence, consumed_at) values (?, ?, ?)"
                        + " on conflict (dedupe_key) do nothing")
                .params(dedupeKey, sequence, utc(clock.instant()))
                .update() == 1;
    }

    /**
     * An upsert, because a SKU's first event may be a hold rather than an adjustment — the ledger does
     * not promise this service saw the stock arrive. The delta is applied to whatever is there.
     */
    private void delta(List<Line> lines, int onHandSign, int reservedSign, Instant at) {
        for (Line line : lines) {
            long onHand = onHandSign * line.quantity();
            long reserved = reservedSign * line.quantity();
            jdbc.sql("insert into store_availability (sku, on_hand, reserved, available, updated_at)"
                            + " values (?, ?, ?, ?, ?)"
                            + " on conflict (sku) do update set"
                            + "   on_hand = store_availability.on_hand + excluded.on_hand,"
                            + "   reserved = store_availability.reserved + excluded.reserved,"
                            + "   available = (store_availability.on_hand + excluded.on_hand)"
                            + "             - (store_availability.reserved + excluded.reserved),"
                            + "   updated_at = excluded.updated_at")
                    .params(line.sku().value(), onHand, reserved, onHand - reserved, utc(at))
                    .update();
        }
    }

    /** A set rather than an add: the adjustment carries the level afterwards, not the change. */
    private void absolute(Event.StockAdjusted event) {
        jdbc.sql("insert into store_availability (sku, on_hand, reserved, available, updated_at) values (?, ?, ?, ?, ?)"
                        + " on conflict (sku) do update set"
                        + "   on_hand = excluded.on_hand, reserved = excluded.reserved,"
                        + "   available = excluded.available, updated_at = excluded.updated_at")
                .params(event.sku().value(), event.onHand(), event.reserved(),
                        event.onHand() - event.reserved(), utc(event.occurredAt()))
                .update();
    }

    /** Counted on the day the ledger decided the sale, by the ledger's clock, in UTC. */
    private void sold(List<Line> lines, Instant at) {
        LocalDate day = at.atZone(ZoneOffset.UTC).toLocalDate();
        for (Line line : lines) {
            jdbc.sql("insert into store_sales_daily (sku, day, units) values (?, ?, ?)"
                            + " on conflict (sku, day) do update set units = store_sales_daily.units + excluded.units")
                    .params(line.sku().value(), day, line.quantity())
                    .update();
        }
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
