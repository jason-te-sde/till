package io.till.store;

import io.till.client.TillClient;
import io.till.core.Codec;
import io.till.core.IdempotencyKey;
import io.till.core.Line;
import io.till.core.Outcome;
import io.till.core.OutboxEntry;
import io.till.core.Reservation;
import io.till.core.ReservationId;
import io.till.core.ReservationState;
import io.till.core.Sku;
import io.till.core.StockItem;
import io.till.core.Till;
import io.till.core.mem.InMemoryLedger;
import io.till.store.events.Projector;
import io.till.store.ledger.Holds;
import io.till.store.ledger.LedgerRejection;
import io.till.store.ledger.LedgerUnavailableException;
import io.till.store.ledger.OperatorLedger;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * The real reservation kernel, in memory, standing in for the ledger service.
 *
 * <p>Not a stub. Every reserve, commit and release the store makes in these tests is decided by the
 * same {@code Kernel} the ledger service runs — the same idempotency, the same expiry rules, the same
 * refusals — only without HTTP in between. A stub would answer the way its author expected the ledger
 * to answer; this answers the way the ledger does.
 *
 * <p>{@link #deliver(Projector)} plays the part of the outbox, Kafka and the consumer: it hands every
 * event the kernel has written to the store's projection, in order, exactly as the running system
 * would, and can hand them over twice to exercise redelivery.
 */
public final class EmbeddedLedger implements Holds, OperatorLedger {

    private final MutableClock clock;
    private volatile InMemoryLedger ledger;
    private volatile Till till;
    private volatile boolean unavailable;
    private volatile boolean broken;

    EmbeddedLedger(MutableClock clock) {
        this.clock = clock;
        reset();
    }

    /** A fresh, empty ledger — every test starts from nothing it did not make itself. */
    public void reset() {
        this.ledger = new InMemoryLedger();
        this.till = Till.builder(ledger).clock(clock).build();
        this.unavailable = false;
        this.broken = false;
    }

    /**
     * @param value whether every call should fail as if the ledger could not be reached
     */
    public void unavailable(boolean value) {
        this.unavailable = value;
    }

    /**
     * @param value whether every call should fail the way a bug does — not a refusal, not an outage
     */
    public void broken(boolean value) {
        this.broken = value;
    }

    /**
     * Stocks a game directly, as a delivery would.
     *
     * @param sku which game
     * @param units how many
     */
    public void stock(String sku, long units) {
        Outcome outcome = till.adjust(IdempotencyKey.of("test-stock." + sku + "." + ledger.appliedCount()), Sku.of(sku), units);
        if (outcome instanceof Outcome.Rejected rejected) {
            throw new AssertionError("could not stock " + sku + ": " + rejected);
        }
    }

    /**
     * @param sku a game
     * @return its stock row, if it has one
     */
    public Optional<StockItem> level(String sku) {
        return ledger.stock(Sku.of(sku));
    }

    /**
     * @param id a reservation
     * @return it, if it exists
     */
    public Optional<Reservation> reservation(ReservationId id) {
        return ledger.reservation(id);
    }

    /**
     * Writes off every hold whose deadline has passed, as the ledger service's sweeper does.
     *
     * @return how many were written off
     */
    public int sweep() {
        return till.sweep(10_000);
    }

    /**
     * Hands every unpublished event to the projection, then marks them published.
     *
     * @param projector the store's projection
     * @return how many events were delivered
     */
    public int deliver(Projector projector) {
        List<OutboxEntry> pending = ledger.unpublished(10_000);
        for (OutboxEntry entry : pending) {
            projector.apply(entry.dedupeKey(), entry.sequence(), entry.event());
        }
        ledger.markPublished(pending.stream().map(OutboxEntry::sequence).toList(), clock.instant());
        return pending.size();
    }

    /**
     * Hands every event ever written to the projection again — what a consumer-group rebalance, or a
     * republished batch, looks like from the consuming side.
     *
     * @param projector the store's projection
     */
    public void redeliverEverything(Projector projector) {
        for (OutboxEntry entry : ledger.allEvents()) {
            projector.apply(entry.dedupeKey(), entry.sequence(), entry.event());
        }
    }

    // --- Holds ------------------------------------------------------------------------------------

    @Override
    public Outcome reserve(IdempotencyKey key, List<Line> lines, Duration holdFor) {
        checkAvailable();
        // A fresh id per call, as the ledger service mints one per request. A retry with the same key
        // replays the first decision — including its id — because the fingerprint ignores the id.
        return till.reserve(key, ReservationId.random(), lines, holdFor);
    }

    @Override
    public Outcome commit(IdempotencyKey key, ReservationId id) {
        checkAvailable();
        return till.commit(key, id);
    }

    @Override
    public Outcome release(IdempotencyKey key, ReservationId id) {
        checkAvailable();
        return till.release(key, id);
    }

    // --- OperatorLedger ---------------------------------------------------------------------------

    @Override
    public TillClient.StockPage stock(int limit, String after) {
        checkAvailable();
        List<StockItem> page = ledger.listStock(Optional.ofNullable(after).map(Sku::of), limit);
        String next = page.size() == limit && !page.isEmpty() ? page.get(page.size() - 1).sku().value() : null;
        return new TillClient.StockPage(
                page.stream()
                        .map(s -> new TillClient.StockView(s.sku(), s.onHand(), s.reserved(), s.available(), s.shards()))
                        .toList(),
                next);
    }

    @Override
    public List<TillClient.ReservationView> reservations(ReservationState state, int limit) {
        checkAvailable();
        return ledger.listReservations(Optional.ofNullable(state), limit).stream()
                .map(r -> new TillClient.ReservationView(
                        r.id(), r.state(), r.effectiveState(clock.instant()), r.lines(), r.createdAt(), r.expiresAt()))
                .toList();
    }

    @Override
    public TillClient.OutboxPage outbox(int limit) {
        checkAvailable();
        return new TillClient.OutboxPage(
                ledger.backlog(),
                ledger.unpublished(limit).stream()
                        .map(e -> new TillClient.OutboxItem(e.sequence(), e.dedupeKey(), e.recordedAt(), Codec.encodeEvent(e.event())))
                        .toList());
    }

    @Override
    public TillClient.StockView adjust(IdempotencyKey key, Sku sku, long delta) {
        checkAvailable();
        Outcome outcome = till.adjust(key, sku, delta);
        if (outcome instanceof Outcome.Rejected rejected) {
            throw new LedgerRejection(rejected);
        }
        Outcome.Adjusted adjusted = (Outcome.Adjusted) outcome;
        return new TillClient.StockView(adjusted.sku(), adjusted.onHand(), adjusted.reserved(), adjusted.available(),
                ledger.stock(sku).map(StockItem::shards).orElse(1));
    }

    @Override
    public TillClient.StockView shard(IdempotencyKey key, Sku sku, int shards) {
        checkAvailable();
        Outcome outcome = till.shard(key, sku, shards);
        if (outcome instanceof Outcome.Rejected rejected) {
            throw new LedgerRejection(rejected);
        }
        StockItem level = ledger.stock(sku).orElseThrow();
        return new TillClient.StockView(level.sku(), level.onHand(), level.reserved(), level.available(), level.shards());
    }

    private void checkAvailable() {
        if (broken) {
            throw new IllegalStateException("the test broke the ledger client");
        }
        if (unavailable) {
            throw new LedgerUnavailableException("the test made the ledger unreachable", new java.io.IOException("connection refused"));
        }
    }
}
