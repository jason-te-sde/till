package io.till.testkit;

import io.till.core.BatchDecision;
import io.till.core.BatchSnapshot;
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
import io.till.core.StockShard;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A ledger with a known bug in it, so that the suite can be tested rather than trusted.
 *
 * <p>Each flaw is implemented by breaking the {@link Ledger} contract in one specific way, which is
 * where these bugs live in real systems: the rules are usually fine and the adapter underneath them
 * is where the race is. A batch's load and write (ADR 16) are broken the same way as a single
 * command's, so that a suite driving batches can be tested too.
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
                loaded.stock().forEach((sku, shards) -> {
                    builder.absent(sku);
                    shards.forEach(shard -> builder.shard(
                            new StockShard(sku, shard.index(), shard.onHand(), 0, shard.version())));
                });
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

    @Override
    public BatchSnapshot loadBatch(List<Command> commands) {
        BatchSnapshot loaded = delegate.loadBatch(commands);
        return switch (flaw) {
            case NO_IDEMPOTENCY -> new BatchSnapshot(loaded.stock(), loaded.reservations(), Map.of());
            case RESERVED_IGNORED -> {
                Map<Sku, List<StockShard>> stock = new LinkedHashMap<>();
                loaded.stock().forEach((sku, shards) -> stock.put(sku, shards.stream()
                        .map(shard -> new StockShard(sku, shard.index(), shard.onHand(), 0, shard.version()))
                        .toList()));
                yield new BatchSnapshot(stock, loaded.reservations(), loaded.records());
            }
            default -> loaded;
        };
    }

    @Override
    public boolean applyBatch(BatchDecision decision) {
        return switch (flaw) {
            case LOST_UPDATE -> delegate.applyBatch(blind(decision));
            case PARTIAL_APPLY -> delegate.applyBatch(new BatchDecision(
                    decision.outcomes(), decision.stock(), decision.states(), decision.inserts(), List.of(),
                    decision.records()));
            case NO_IDEMPOTENCY -> delegate.applyBatch(new BatchDecision(
                    decision.outcomes(), decision.stock(), decision.states(), decision.inserts(), decision.events(),
                    List.of()));
            default -> delegate.applyBatch(decision);
        };
    }

    /** A batch with every expected version replaced by whatever is there now, as {@link #applyBlind} does a decision. */
    private BatchDecision blind(BatchDecision decision) {
        List<BatchDecision.StockWrite> stock = decision.stock().stream()
                .map(write -> {
                    long current = currentVersion(write.sku(), write.shard());
                    return new BatchDecision.StockWrite(
                            write.sku(), write.shard(), write.onHand(), write.reserved(), current,
                            Math.max(write.newVersion(), current + 1));
                })
                .toList();
        List<Mutation.SetReservationState> states = decision.states().stream()
                .map(set -> new Mutation.SetReservationState(set.reservationId(), set.state(), currentVersion(set.reservationId())))
                .toList();
        return new BatchDecision(
                decision.outcomes(), stock, states, decision.inserts(), decision.events(), decision.records());
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
                                                                    m.sku(),
                                                                    m.shard(),
                                                                    m.onHand(),
                                                                    m.reserved(),
                                                                    currentVersion(m.sku(), m.shard()));
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

    private long currentVersion(Sku sku, int shard) {
        return inspector.allShards().stream()
                .filter(row -> row.sku().equals(sku) && row.index() == shard)
                .mapToLong(StockShard::version)
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
    public List<StockShard> allShards() {
        return inspector.allShards();
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
