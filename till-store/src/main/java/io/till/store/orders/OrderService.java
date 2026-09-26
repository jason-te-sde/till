package io.till.store.orders;

import io.till.core.IdempotencyKey;
import io.till.core.Line;
import io.till.core.Outcome;
import io.till.core.RejectionCode;
import io.till.store.StoreProperties;
import io.till.store.catalogue.Game;
import io.till.store.catalogue.Games;
import io.till.store.ledger.Holds;
import io.till.store.ledger.LedgerKeys;
import io.till.store.ledger.LedgerRejection;
import io.till.store.web.NotFoundException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Placing, paying for and cancelling orders.
 *
 * <h2>No transaction spans a call to the ledger</h2>
 *
 * <p>Each method reads, calls the ledger over HTTP, then writes — and deliberately holds no database
 * transaction across the call. A transaction held open while waiting on another service turns that
 * service's slowest second into this one's longest lock, and turns its outage into this one's
 * connection-pool exhaustion.
 *
 * <p>What makes that safe is that every step is idempotent on its own. The ledger call carries a key
 * derived from the customer's; the insert is {@code on conflict do nothing} on the same key; a status
 * change is guarded by the status it expects. A crash between any two steps leaves something a retry
 * of the same request completes, and at worst a hold nobody recorded — which the ledger gives back when
 * it expires, because that is what holds are for.
 *
 * <h2>The HTTP answer is the fast path; the event stream is the truth</h2>
 *
 * <p>When a payment's response is lost on the way back, the order here still says pending. It does
 * not stay that way: the ledger's commit event reaches the projection, which moves the order to paid.
 * An order's status converges on the ledger's whichever way the news arrives first.
 */
@Service
public class OrderService {

    /** Most orders "my orders" shows. Older ones are not gone; this is a page, not a history export. */
    static final int HISTORY = 50;

    private final Games games;
    private final Orders orders;
    private final Holds holds;
    private final StoreProperties.Checkout rules;
    private final Clock clock;

    OrderService(Games games, Orders orders, Holds holds, StoreProperties properties, Clock clock) {
        this.games = games;
        this.orders = orders;
        this.holds = holds;
        this.rules = properties.checkout();
        this.clock = clock;
    }

    /**
     * Places an order, holding its stock.
     *
     * @param customer who is buying
     * @param clientKey their key for this checkout attempt
     * @param requested what they asked for
     * @return the order — the one already placed under this key, if there is one
     * @throws IllegalArgumentException for a basket the store would never accept
     * @throws LedgerRejection if the ledger refused, most often for want of stock
     * @throws KeyReusedException if this key already placed a different order
     */
    public Order place(String customer, String clientKey, List<Requested> requested) {
        List<Requested> basket = validate(requested);

        Optional<Order> earlier = orders.findByKey(customer, clientKey);
        if (earlier.isPresent()) {
            // A retry, most likely — a double click, or a response lost on the way back. It gets the
            // order it already placed, without asking the ledger again. Unless it is not a retry at all
            // but a different basket under the same key, which the customer has to be told about
            // rather than silently handed an order for something else.
            if (!sameBasket(earlier.get(), basket)) {
                throw new KeyReusedException();
            }
            return earlier.get();
        }

        Map<String, Game> catalogue = games.findAll(basket.stream().map(Requested::sku).toList());
        for (Requested line : basket) {
            if (!catalogue.containsKey(line.sku())) {
                throw new IllegalArgumentException("the store does not sell '" + line.sku() + "'");
            }
        }

        IdempotencyKey key = LedgerKeys.scoped("order", customer, clientKey);
        List<Line> ledgerLines = basket.stream().map(line -> Line.of(line.sku(), line.quantity())).toList();
        Outcome outcome = holds.reserve(key, ledgerLines, rules.holdFor());
        if (outcome instanceof Outcome.Rejected rejected) {
            if (rejected.code() == RejectionCode.IDEMPOTENCY_KEY_REUSED) {
                throw new KeyReusedException();
            }
            throw new LedgerRejection(rejected);
        }
        Outcome.Reserved reserved = (Outcome.Reserved) outcome;

        // Prices are read here, on the server, from the catalogue — never taken from the request.
        List<Order.Line> lines = new ArrayList<>();
        long total = 0;
        for (Requested line : basket) {
            Game game = catalogue.get(line.sku());
            Order.Line orderLine = new Order.Line(game.sku(), game.title(), game.priceCents(), line.quantity());
            lines.add(orderLine);
            total = Math.addExact(total, orderLine.subtotalCents());
        }
        Order order = new Order(
                UUID.randomUUID(),
                customer,
                clientKey,
                reserved.id(),
                Order.Status.PENDING,
                total,
                rules.currency(),
                clock.instant(),
                reserved.expiresAt(),
                null,
                lines);
        orders.insertIfAbsent(order);
        // Read back rather than returning the object built above: if a concurrent copy of this very
        // request won the insert, its order is the one that exists, and this caller should see it.
        return orders.findByKey(customer, clientKey).orElseThrow();
    }

    /**
     * Pays for an order, turning its hold into a sale.
     *
     * <p>No money moves: this is a demonstration store, and the page says so. What is real is
     * everything around it — the hold, the commit, and a paid order that cannot be paid for twice.
     *
     * @param customer who is paying
     * @param orderId which order
     * @param clientKey their key for this payment attempt
     * @return the order, paid
     * @throws NotFoundException if the order does not exist or is not theirs
     * @throws OrderClosedException if it was cancelled, or expired first
     */
    public Order pay(String customer, UUID orderId, String clientKey) {
        Order order = owned(customer, orderId);
        switch (order.effectiveStatus(clock.instant())) {
            case PAID -> {
                return order;
            }
            case CANCELLED -> throw new OrderClosedException(Order.Status.CANCELLED, "this order was cancelled");
            case EXPIRED -> {
                orders.close(order.id(), Order.Status.EXPIRED, order.expiresAt());
                throw new OrderClosedException(Order.Status.EXPIRED, "the hold on this order ran out before it was paid for");
            }
            case PENDING -> {
                // Below.
            }
        }

        Outcome outcome = holds.commit(LedgerKeys.scoped("pay", customer, orderId.toString(), clientKey), order.reservationId());
        switch (outcome) {
            case Outcome.Committed committed -> orders.close(order.id(), Order.Status.PAID, committed.at());
            case Outcome.Rejected rejected -> {
                switch (rejected.code()) {
                    // Already paid: an earlier attempt with a different key succeeded and its answer
                    // was lost. The order is paid, and saying otherwise would invite a second payment.
                    case ALREADY_COMMITTED -> orders.close(order.id(), Order.Status.PAID, clock.instant());
                    case RESERVATION_EXPIRED -> {
                        orders.close(order.id(), Order.Status.EXPIRED, order.expiresAt());
                        throw new OrderClosedException(
                                Order.Status.EXPIRED, "the hold on this order ran out before it was paid for");
                    }
                    case ALREADY_RELEASED -> {
                        orders.close(order.id(), Order.Status.CANCELLED, clock.instant());
                        throw new OrderClosedException(Order.Status.CANCELLED, "this order was cancelled");
                    }
                    default -> throw new LedgerRejection(rejected);
                }
            }
            default -> throw new IllegalStateException("the ledger answered a commit with " + outcome);
        }
        return owned(customer, orderId);
    }

    /**
     * Cancels an order, giving its stock back.
     *
     * @param customer who is cancelling
     * @param orderId which order
     * @param clientKey their key for this attempt
     * @return the order, cancelled — or as it stands, if it had already finished some other way
     * @throws NotFoundException if the order does not exist or is not theirs
     * @throws OrderClosedException if it was already paid for
     */
    public Order cancel(String customer, UUID orderId, String clientKey) {
        Order order = owned(customer, orderId);
        switch (order.status()) {
            case PAID -> throw new OrderClosedException(Order.Status.PAID, "this order is paid for and cannot be cancelled");
            case CANCELLED, EXPIRED -> {
                return order;
            }
            case PENDING -> {
                // Below. Even past its deadline: releasing an expired hold is how the ledger writes the
                // expiry off, and it answers "released" — the customer wanted it gone, and it is.
            }
        }

        Outcome outcome = holds.release(LedgerKeys.scoped("cancel", customer, orderId.toString(), clientKey), order.reservationId());
        switch (outcome) {
            case Outcome.Released released -> orders.close(order.id(), Order.Status.CANCELLED, released.at());
            case Outcome.Rejected rejected -> {
                if (rejected.code() == RejectionCode.ALREADY_COMMITTED) {
                    orders.close(order.id(), Order.Status.PAID, clock.instant());
                    throw new OrderClosedException(Order.Status.PAID, "this order is paid for and cannot be cancelled");
                }
                throw new LedgerRejection(rejected);
            }
            default -> throw new IllegalStateException("the ledger answered a release with " + outcome);
        }
        return owned(customer, orderId);
    }

    /**
     * @param customer who is asking
     * @return their most recent orders, newest first
     */
    public List<Order> history(String customer) {
        return orders.forCustomer(customer, HISTORY);
    }

    /**
     * @param customer who is asking
     * @param orderId which order
     * @return the order
     * @throws NotFoundException if it does not exist or is not theirs — the same answer for both, so
     *     that order ids cannot be probed for existence
     */
    public Order owned(String customer, UUID orderId) {
        return orders.find(customer, orderId)
                .orElseThrow(() -> new NotFoundException("No such order", "there is no order " + orderId));
    }

    /**
     * @return the current instant, for rendering an order's effective status
     */
    public Instant now() {
        return clock.instant();
    }

    private List<Requested> validate(List<Requested> requested) {
        if (requested == null || requested.isEmpty()) {
            throw new IllegalArgumentException("an order needs at least one game");
        }
        if (requested.size() > rules.maxLines()) {
            throw new IllegalArgumentException("an order can have at most " + rules.maxLines() + " different games");
        }
        Set<String> seen = new LinkedHashSet<>();
        for (Requested line : requested) {
            if (line.quantity() < 1 || line.quantity() > rules.maxQuantity()) {
                throw new IllegalArgumentException(
                        "between 1 and " + rules.maxQuantity() + " copies of a game per order, got " + line.quantity());
            }
            if (!seen.add(line.sku())) {
                // Merging them would be a guess about what the customer meant. One line per game is
                // what the ledger requires too, so refusing here gives the clearer message.
                throw new IllegalArgumentException("'" + line.sku() + "' appears twice; send one line per game");
            }
        }
        return List.copyOf(requested);
    }

    private static boolean sameBasket(Order order, List<Requested> basket) {
        if (order.lines().size() != basket.size()) {
            return false;
        }
        Map<String, Integer> placed = new java.util.HashMap<>();
        order.lines().forEach(line -> placed.put(line.sku(), line.quantity()));
        return basket.stream().allMatch(line -> Integer.valueOf(line.quantity()).equals(placed.get(line.sku())));
    }

    /**
     * One line of a basket as the customer sent it.
     *
     * @param sku which game
     * @param quantity how many copies
     */
    public record Requested(String sku, int quantity) {}

    /** The customer's key already placed a different order. */
    public static class KeyReusedException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        KeyReusedException() {
            super("this Idempotency-Key already placed a different order; use a new key for a new basket");
        }
    }
}
