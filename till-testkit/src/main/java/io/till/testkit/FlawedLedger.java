package io.till.testkit;

import io.till.core.Command;
import io.till.core.Decision;
import io.till.core.Ledger;
import io.till.core.LedgerInspector;
import io.till.core.Mutation;
import io.till.core.OutboxEntry;
import io.till.core.Reservation;
import io.till.core.ReservationState;
import io.till.core.ReservationId;
import io.till.core.Sku;
import io.till.core.Snapshot;
import io.till.core.StockItem;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * A ledger with a known bug in it, so that the suite can be tested rather than trusted.
 *
 * <p>Each flaw is implemented by breaking the {@link Ledger} contract in one specific way, which is
 * where these bugs live in real systems: the rules are usually fine and the adapter underneath them
 * is where the race is.
 *
 * @see Flaw
 */
public final class FlawedLedger implements Ledger, LedgerInspector {

    private final Ledger delegate;
    private final LedgerInspector inspector;
    private final Flaw flaw;

    /**
     * @param delegate the correct ledger to break
     * @param flaw which bug to introduce
     * @param <L> a ledger that can also be inspected
     */
    public <L extends Ledger & LedgerInspector> FlawedLedger(L delegate, Flaw flaw) {
        this.delegate = delegate;
        this.inspector = delegate;
        this.flaw = flaw;
    }

    @Override
    public Snapshot load(Command command, Instant now, int reclaimLimit) {
        Snapshot loaded = delegate.load(command, now, reclaimLimit);
        return switch (flaw) {
            case NO_IDEMPOTENCY ->
                    new Snapshot(loaded.stock(), loaded.reservation(), Optional.empty(), loaded.reclaimable());
            case RESERVED_IGNORED -> {
                Snapshot.Builder builder = Snapshot.builder();
                loaded.stock()
                        .values()
                        .forEach(
                                item ->
                                        builder.stock(
                                                new StockItem(item.sku(), item.onHand(), 0, item.version())));
                loaded.reservation().ifPresent(builder::reservation);
                loaded.recordedOutcome().ifPresent(builder::recordedOutcome);
                builder.reclaimable(loaded.reclaimable());
                yield builder.build();
            }
            default -> loaded;
        };
    }

    @Override
    public boolean apply(Decision decision) {
        return switch (flaw) {
            case LOST_UPDATE -> applyBlind(decision);
            case PARTIAL_APPLY ->
                    delegate.apply(
                            new Decision(decision.outcome(), decision.mutations(), List.of(), decision.outcomeRecord()));
            case NO_IDEMPOTENCY ->
                    delegate.apply(
                            new Decision(
                                    decision.outcome(), decision.mutations(), decision.events(), Optional.empty()));
            default -> delegate.apply(decision);
        };
    }

    /**
     * Writes a decision with every expected version replaced by whatever is there now, which is what
     * an implementation without a version column does.
     */
    private boolean applyBlind(Decision decision) {
        List<Mutation> rewritten =
                decision.mutations().stream()
                        .map(
                                mutation ->
                                        switch (mutation) {
                                            case Mutation.PutStock m ->
                                                    (Mutation)
                                                            new Mutation.PutStock(
                                                                    m.sku(), m.onHand(), m.reserved(), currentVersion(m.sku()));
                                            case Mutation.SetReservationState m ->
                                                    new Mutation.SetReservationState(
                                                            m.reservationId(),
                                                            m.state(),
                                                            currentVersion(m.reservationId()));
                                            case Mutation.InsertReservation m -> m;
                                        })
                        .toList();
        return delegate.apply(
                new Decision(decision.outcome(), rewritten, decision.events(), decision.outcomeRecord()));
    }

    private long currentVersion(Sku sku) {
        return inspector.allStock().stream()
                .filter(item -> item.sku().equals(sku))
                .mapToLong(StockItem::version)
                .findFirst()
                .orElse(StockItem.ABSENT);
    }

    private long currentVersion(ReservationId id) {
        return inspector.allReservations().stream()
                .filter(reservation -> reservation.id().equals(id))
                .mapToLong(Reservation::version)
                .findFirst()
                .orElse(0);
    }

    @Override
    public Optional<StockItem> stock(Sku sku) {
        return inspector.stock(sku);
    }

    @Override
    public Optional<Reservation> reservation(ReservationId id) {
        return inspector.reservation(id);
    }

    @Override
    public List<StockItem> listStock(Optional<Sku> after, int limit) {
        return inspector.listStock(after, limit);
    }

    @Override
    public List<Reservation> listReservations(Optional<ReservationState> state, int limit) {
        return inspector.listReservations(state, limit);
    }

    @Override
    public List<StockItem> allStock() {
        return inspector.allStock();
    }

    @Override
    public List<Reservation> allReservations() {
        return inspector.allReservations();
    }

    @Override
    public List<OutboxEntry> allEvents() {
        return inspector.allEvents();
    }
}
