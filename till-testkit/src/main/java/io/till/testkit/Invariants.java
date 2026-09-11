package io.till.testkit;

import io.till.core.Event;
import io.till.core.LedgerInspector;
import io.till.core.Line;
import io.till.core.OutboxEntry;
import io.till.core.Reservation;
import io.till.core.ReservationId;
import io.till.core.ReservationState;
import io.till.core.Sku;
import io.till.core.StockItem;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The properties that must hold after every single step of a run.
 *
 * <p>Checking continuously rather than at the end is the whole point. A ledger that oversells ten
 * units and then has them released looks perfectly balanced by the time a run finishes, and an
 * end-state assertion would pass with the bug still in it.
 *
 * <p>The properties:
 *
 * <ul>
 *   <li><b>Possible levels.</b> {@code 0 <= reserved <= onHand} for every SKU. The most direct
 *       statement of "never oversold": stock cannot be promised twice, and cannot be promised at all
 *       if it is not there.
 *   <li><b>Conservation.</b> A SKU's {@code reserved} equals the sum of the quantities of every
 *       reservation whose stored state is {@code HELD}. Not "roughly equals" and not "eventually
 *       equals": every hold is counted exactly once, at every instant.
 *   <li><b>The ledger agrees with its own audit log.</b> {@code onHand} equals the adjustments in
 *       the outbox minus the commits in it, and {@code reserved} equals the reservations in it minus
 *       the commits, releases and expiries. This is the invariant that catches a change written
 *       without its event, or an event written for a change that did not happen — the two failure
 *       modes a transactional outbox exists to prevent, and the two that only show up months later
 *       as a downstream system that has quietly drifted.
 *   <li><b>Terminal states are terminal.</b> A reservation goes from {@code HELD} to exactly one of
 *       the other three and then never moves again.
 *   <li><b>Versions never go backwards</b>, for a stock row or for a reservation. A version that
 *       decreases means an optimistic check somewhere compared against the wrong number.
 *   <li><b>Deduplication keys are unique.</b> Two outbox rows with the same key would mean a
 *       consumer silently dropping a real event.
 *   <li><b>Events match states.</b> A committed reservation has a reserved event and a committed
 *       event and nothing else; a held one has only a reserved event.
 * </ul>
 *
 * <p>Stateful on purpose: several of these are about history rather than about the current instant,
 * and folding each new outbox row in once keeps a check cheap enough to run tens of thousands of
 * times.
 *
 * <p>Not thread safe, and not meant to be: one simulator drives one of these.
 */
public final class Invariants {

    private final long seed;

    private final Map<Sku, Ledgered> ledgered = new HashMap<>();
    private final Map<ReservationId, ReservationState> lastState = new HashMap<>();
    private final Map<Sku, Long> maxStockVersion = new HashMap<>();
    private final Map<ReservationId, Long> maxReservationVersion = new HashMap<>();
    private final Map<ReservationId, Set<String>> eventKinds = new HashMap<>();
    private final Set<String> dedupeKeys = new HashSet<>();

    private long sequenceSeen;
    private long checks;

    /**
     * @param seed the run's seed, quoted in every failure
     */
    public Invariants(long seed) {
        this.seed = seed;
    }

    /**
     * How many times {@link #check} has run.
     *
     * <p>Worth asserting on. A safety suite that passes because it checked nothing is the failure
     * mode a simulator is most exposed to.
     *
     * @return the check count
     */
    public long checkCount() {
        return checks;
    }

    /**
     * Verifies every property against the whole ledger.
     *
     * @param step which step this is, for the failure message
     * @param now the instant the step ran at
     * @param inspector the ledger to look at
     * @throws InvariantViolation on the first property that fails
     */
    public void check(long step, Instant now, LedgerInspector inspector) {
        checks++;
        List<StockItem> stock = inspector.allStock();
        List<Reservation> reservations = inspector.allReservations();

        foldNewEvents(step, inspector.allEvents());
        possibleLevels(step, stock);
        conservation(step, stock, reservations);
        agreesWithItsAuditLog(step, stock);
        terminalStatesAreTerminal(step, reservations);
        versionsNeverGoBackwards(step, stock, reservations);
        eventsMatchStates(step, now, reservations);
    }

    /** The running totals one SKU's outbox rows imply. */
    private static final class Ledgered {
        private long adjusted;
        private long reserved;
        private long committed;
        private long released;
        private long expired;
    }

    private Ledgered forSku(Sku sku) {
        return ledgered.computeIfAbsent(sku, ignored -> new Ledgered());
    }

    private void foldNewEvents(long step, List<OutboxEntry> entries) {
        for (OutboxEntry entry : entries) {
            if (entry.sequence() <= sequenceSeen) {
                continue;
            }
            sequenceSeen = entry.sequence();
            if (!dedupeKeys.add(entry.dedupeKey())) {
                throw new InvariantViolation(
                        "Deduplication keys are unique",
                        seed,
                        step,
                        "two outbox rows share the key " + entry.dedupeKey() + "; a consumer would drop one");
            }
            switch (entry.event()) {
                case Event.StockAdjusted e -> forSku(e.sku()).adjusted += e.delta();
                case Event.StockReserved e -> {
                    e.lines().forEach(line -> forSku(line.sku()).reserved += line.quantity());
                    kinds(e.reservationId()).add("reserved");
                }
                case Event.StockCommitted e -> {
                    e.lines().forEach(line -> forSku(line.sku()).committed += line.quantity());
                    kinds(e.reservationId()).add("committed");
                }
                case Event.StockReleased e -> {
                    e.lines().forEach(line -> forSku(line.sku()).released += line.quantity());
                    kinds(e.reservationId()).add("released");
                }
                case Event.StockExpired e -> {
                    e.lines().forEach(line -> forSku(line.sku()).expired += line.quantity());
                    kinds(e.reservationId()).add("expired");
                }
            }
        }
    }

    private Set<String> kinds(ReservationId id) {
        return eventKinds.computeIfAbsent(id, ignored -> new HashSet<>());
    }

    private void possibleLevels(long step, List<StockItem> stock) {
        for (StockItem item : stock) {
            if (item.onHand() < 0 || item.reserved() < 0 || item.reserved() > item.onHand()) {
                throw new InvariantViolation("Possible levels", seed, step, item.toString());
            }
        }
    }

    private void conservation(long step, List<StockItem> stock, List<Reservation> reservations) {
        Map<Sku, Long> heldBySku = new HashMap<>();
        for (Reservation reservation : reservations) {
            if (reservation.state() != ReservationState.HELD) {
                continue;
            }
            for (Line line : reservation.lines()) {
                heldBySku.merge(line.sku(), line.quantity(), Long::sum);
            }
        }
        for (StockItem item : stock) {
            long held = heldBySku.getOrDefault(item.sku(), 0L);
            if (item.reserved() != held) {
                throw new InvariantViolation(
                        "Conservation",
                        seed,
                        step,
                        item.sku() + " says reserved=" + item.reserved() + " but held reservations sum to " + held);
            }
            heldBySku.remove(item.sku());
        }
        if (!heldBySku.isEmpty()) {
            throw new InvariantViolation(
                    "Conservation",
                    seed,
                    step,
                    "reservations hold " + heldBySku + " for SKUs with no stock row at all");
        }
    }

    private void agreesWithItsAuditLog(long step, List<StockItem> stock) {
        for (StockItem item : stock) {
            Ledgered sums = forSku(item.sku());
            long expectedOnHand = sums.adjusted - sums.committed;
            if (item.onHand() != expectedOnHand) {
                throw new InvariantViolation(
                        "The ledger agrees with its own audit log",
                        seed,
                        step,
                        item.sku() + " holds " + item.onHand() + " but its events imply " + expectedOnHand
                                + " (adjusted " + sums.adjusted + ", committed " + sums.committed + ")");
            }
            long expectedReserved = sums.reserved - sums.committed - sums.released - sums.expired;
            if (item.reserved() != expectedReserved) {
                throw new InvariantViolation(
                        "The ledger agrees with its own audit log",
                        seed,
                        step,
                        item.sku() + " reserves " + item.reserved() + " but its events imply " + expectedReserved
                                + " (reserved " + sums.reserved + ", committed " + sums.committed + ", released "
                                + sums.released + ", expired " + sums.expired + ")");
            }
        }
    }

    private void terminalStatesAreTerminal(long step, List<Reservation> reservations) {
        for (Reservation reservation : reservations) {
            ReservationState before = lastState.put(reservation.id(), reservation.state());
            if (before == null || before == reservation.state()) {
                continue;
            }
            if (before.isTerminal()) {
                throw new InvariantViolation(
                        "Terminal states are terminal",
                        seed,
                        step,
                        reservation.id() + " moved from " + before + " to " + reservation.state());
            }
        }
    }

    private void versionsNeverGoBackwards(long step, List<StockItem> stock, List<Reservation> reservations) {
        for (StockItem item : stock) {
            Long seen = maxStockVersion.put(item.sku(), item.version());
            if (seen != null && item.version() < seen) {
                throw new InvariantViolation(
                        "Versions never go backwards",
                        seed,
                        step,
                        item.sku() + " was at version " + seen + " and is now at " + item.version());
            }
        }
        for (Reservation reservation : reservations) {
            Long seen = maxReservationVersion.put(reservation.id(), reservation.version());
            if (seen != null && reservation.version() < seen) {
                throw new InvariantViolation(
                        "Versions never go backwards",
                        seed,
                        step,
                        reservation.id() + " was at version " + seen + " and is now at " + reservation.version());
            }
        }
    }

    private void eventsMatchStates(long step, Instant now, List<Reservation> reservations) {
        for (Reservation reservation : reservations) {
            Set<String> kinds = kinds(reservation.id());
            if (!kinds.contains("reserved")) {
                throw new InvariantViolation(
                        "Events match states",
                        seed,
                        step,
                        reservation.id() + " exists with no reserved event; its events are " + kinds);
            }
            String expected =
                    switch (reservation.state()) {
                        case HELD -> null;
                        case COMMITTED -> "committed";
                        case RELEASED -> "released";
                        case EXPIRED -> "expired";
                    };
            if (expected == null) {
                if (kinds.size() != 1) {
                    throw new InvariantViolation(
                            "Events match states",
                            seed,
                            step,
                            reservation.id() + " is still held at " + now + " but has already emitted " + kinds);
                }
            } else if (!kinds.contains(expected)) {
                throw new InvariantViolation(
                        "Events match states",
                        seed,
                        step,
                        reservation.id() + " is " + reservation.state() + " with no " + expected
                                + " event; its events are " + kinds);
            }
        }
    }
}
