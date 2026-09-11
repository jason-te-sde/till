package io.till.core.mem;

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
import io.till.core.ReservationState;
import io.till.core.ReservationId;
import io.till.core.Sku;
import io.till.core.Snapshot;
import io.till.core.StockItem;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A ledger in a few maps.
 *
 * <p>Three jobs, and only the first is obvious:
 *
 * <ul>
 *   <li>It makes till usable with no database at all — a single process that wants correct
 *       reservations can embed it and never think about SQL.
 *   <li>It is what the deterministic simulator runs against, which is why the simulator can do a
 *       hundred thousand operations a second and reproduce a failure from a seed.
 *   <li>It is the reference the PostgreSQL adapter is differentially tested against: the same
 *       seeded schedule against both must produce the same answers and the same final state. That
 *       comparison is worth more than either implementation's own tests, because the two were
 *       written from the same contract and disagree exactly where one of them read it wrong.
 * </ul>
 *
 * <p>Locking is coarse on purpose: one lock, held for the whole of a load and for the whole of an
 * apply, and never across both. That makes each half atomic and leaves the interesting part —
 * deciding against a snapshot that has since moved — exactly as exposed as it is against a real
 * database, which is the part worth testing.
 *
 * <p>Everything is kept forever. There is no compaction, no eviction of old idempotency records and
 * no bound on the outbox, because this is for tests and for single-process embedding. A long-running
 * service wants the PostgreSQL adapter and the retention settings that come with it.
 */
public final class InMemoryLedger implements Ledger, LedgerInspector, Outbox {

    private final ReentrantLock lock = new ReentrantLock();

    private final Map<Sku, StockItem> stock = new LinkedHashMap<>();
    private final Map<ReservationId, Reservation> reservations = new LinkedHashMap<>();
    private final Map<IdempotencyKey, OutcomeRecord> records = new LinkedHashMap<>();
    private final TreeMap<Long, OutboxEntry> outbox = new TreeMap<>();
    private final Set<Long> published = new LinkedHashSet<>();

    private long nextSequence = 1;
    private long applied;
    private long conflicts;

    @Override
    public Snapshot load(Command command, Instant now, int reclaimLimit) {
        lock.lock();
        try {
            Snapshot.Builder builder = Snapshot.builder();

            command.idempotencyKey().map(records::get).ifPresent(builder::recordedOutcome);

            Reservation named = command.targetReservation().map(reservations::get).orElse(null);
            builder.reservation(named);

            Set<Sku> scope = new LinkedHashSet<>(command.declaredSkus());
            if (named != null) {
                scope.addAll(named.skus());
            }

            List<Reservation> reclaimable = reclaimable(command, now, scope, reclaimLimit, named);
            reclaimable.forEach(r -> scope.addAll(r.skus()));
            builder.reclaimable(reclaimable);

            for (Sku sku : scope) {
                StockItem item = stock.get(sku);
                builder.stock(item != null ? item : StockItem.empty(sku));
            }
            return builder.build();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Held reservations past their deadline that are worth writing off now.
     *
     * <p>A sweep takes any of them. Every other command takes only the ones standing in the way of
     * the SKUs it is about, because writing off an unrelated hold would touch a row the command has
     * no reason to touch and turn an unrelated caller's commit into a conflict.
     */
    private List<Reservation> reclaimable(
            Command command, Instant now, Set<Sku> scope, int limit, Reservation named) {
        if (limit <= 0) {
            return List.of();
        }
        boolean sweep = command instanceof Command.Sweep;
        List<Reservation> found = new ArrayList<>();
        for (Reservation reservation : reservations.values()) {
            if (found.size() >= limit) {
                break;
            }
            if (!reservation.isReclaimableAt(now)) {
                continue;
            }
            if (named != null && reservation.id().equals(named.id())) {
                continue;
            }
            if (sweep || reservation.lines().stream().map(Line::sku).anyMatch(scope::contains)) {
                found.add(reservation);
            }
        }
        // Ascending by id, so that two ledgers given the same rows offer them in the same order and
        // a differential test compares like with like.
        found.sort(Comparator.comparing(Reservation::id));
        return found;
    }

    @Override
    public boolean apply(Decision decision) {
        lock.lock();
        try {
            if (!admissible(decision)) {
                conflicts++;
                return false;
            }
            for (Mutation mutation : decision.mutations()) {
                switch (mutation) {
                    case Mutation.PutStock m ->
                            stock.put(
                                    m.sku(),
                                    new StockItem(m.sku(), m.onHand(), m.reserved(), m.expectedVersion() + 1));
                    case Mutation.InsertReservation m -> reservations.put(m.reservation().id(), m.reservation());
                    case Mutation.SetReservationState m ->
                            reservations.computeIfPresent(
                                    m.reservationId(), (id, existing) -> existing.withState(m.state()));
                }
            }
            Instant recordedAt = decision.outcomeRecord().map(OutcomeRecord::recordedAt).orElse(null);
            for (Event event : decision.events()) {
                long sequence = nextSequence++;
                outbox.put(
                        sequence,
                        new OutboxEntry(sequence, event, recordedAt != null ? recordedAt : event.occurredAt()));
            }
            decision.outcomeRecord().ifPresent(record -> records.put(record.key(), record));
            applied++;
            return true;
        } finally {
            lock.unlock();
        }
    }

    /** Every precondition the decision was made under, re-checked while holding the lock. */
    private boolean admissible(Decision decision) {
        for (Mutation mutation : decision.mutations()) {
            boolean ok =
                    switch (mutation) {
                        case Mutation.PutStock m -> versionOf(m.sku()) == m.expectedVersion();
                        case Mutation.InsertReservation m -> !reservations.containsKey(m.reservation().id());
                        case Mutation.SetReservationState m -> {
                            Reservation existing = reservations.get(m.reservationId());
                            yield existing != null && existing.version() == m.expectedVersion();
                        }
                    };
            if (!ok) {
                return false;
            }
        }
        // The unique constraint on the idempotency key, which is what makes two copies of one
        // request arriving at the same instant resolve to one execution and one replay.
        return decision.outcomeRecord().map(record -> !records.containsKey(record.key())).orElse(true);
    }

    private long versionOf(Sku sku) {
        StockItem item = stock.get(sku);
        return item != null ? item.version() : StockItem.ABSENT;
    }

    @Override
    public List<OutboxEntry> unpublished(int limit) {
        lock.lock();
        try {
            List<OutboxEntry> entries = new ArrayList<>();
            for (OutboxEntry entry : outbox.values()) {
                if (entries.size() >= limit) {
                    break;
                }
                if (!published.contains(entry.sequence())) {
                    entries.add(entry);
                }
            }
            return entries;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public long backlog() {
        lock.lock();
        try {
            return outbox.size() - published.size();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void markPublished(List<Long> sequences) {
        lock.lock();
        try {
            published.addAll(sequences);
        } finally {
            lock.unlock();
        }
    }

    /**
     * The stock level of one SKU.
     *
     * @param sku which SKU
     * @return the level, or empty if the SKU has no row
     */
    @Override
    public Optional<StockItem> stock(Sku sku) {
        lock.lock();
        try {
            return Optional.ofNullable(stock.get(sku));
        } finally {
            lock.unlock();
        }
    }

    /**
     * Every stock level, in SKU order.
     *
     * @return a snapshot copy
     */
    @Override
    public List<StockItem> listStock(Optional<Sku> after, int limit) {
        lock.lock();
        try {
            return stock.values().stream()
                    .sorted(Comparator.comparing(StockItem::sku))
                    .filter(item -> after.isEmpty() || item.sku().compareTo(after.get()) > 0)
                    .limit(Math.min(limit, LedgerInspector.MAX_PAGE))
                    .toList();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public List<Reservation> listReservations(Optional<ReservationState> state, int limit) {
        lock.lock();
        try {
            return reservations.values().stream()
                    .filter(reservation -> state.isEmpty() || reservation.state() == state.get())
                    // Newest first, with the id breaking ties, so that two reservations created in
                    // the same microsecond come back in a fixed order rather than an arbitrary one.
                    .sorted(
                            Comparator.comparing(Reservation::createdAt)
                                    .reversed()
                                    .thenComparing(Reservation::id))
                    .limit(Math.min(limit, LedgerInspector.MAX_PAGE))
                    .toList();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public List<StockItem> allStock() {
        lock.lock();
        try {
            return stock.values().stream().sorted(Comparator.comparing(StockItem::sku)).toList();
        } finally {
            lock.unlock();
        }
    }

    /**
     * One reservation.
     *
     * @param id which reservation
     * @return the reservation, or empty if there is none
     */
    @Override
    public Optional<Reservation> reservation(ReservationId id) {
        lock.lock();
        try {
            return Optional.ofNullable(reservations.get(id));
        } finally {
            lock.unlock();
        }
    }

    /**
     * Every reservation, in id order.
     *
     * @return a snapshot copy
     */
    @Override
    public List<Reservation> allReservations() {
        lock.lock();
        try {
            return reservations.values().stream().sorted(Comparator.comparing(Reservation::id)).toList();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Every outbox entry ever written, in sequence order.
     *
     * @return a snapshot copy
     */
    @Override
    public List<OutboxEntry> allEvents() {
        lock.lock();
        try {
            return List.copyOf(outbox.values());
        } finally {
            lock.unlock();
        }
    }

    /**
     * How many idempotency keys have been used.
     *
     * @return the record count
     */
    public int recordCount() {
        lock.lock();
        try {
            return records.size();
        } finally {
            lock.unlock();
        }
    }

    /**
     * How many decisions were written.
     *
     * @return the applied count
     */
    public long appliedCount() {
        lock.lock();
        try {
            return applied;
        } finally {
            lock.unlock();
        }
    }

    /**
     * How many decisions were refused because a version had moved.
     *
     * <p>Worth asserting on in a concurrency test: a suite that never conflicts is a suite that
     * never exercised the retry loop, however many threads it started.
     *
     * @return the conflict count
     */
    public long conflictCount() {
        lock.lock();
        try {
            return conflicts;
        } finally {
            lock.unlock();
        }
    }
}
