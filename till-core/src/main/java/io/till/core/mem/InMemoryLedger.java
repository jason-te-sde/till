package io.till.core.mem;

import io.till.core.BatchDecision;
import io.till.core.BatchSnapshot;
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
import io.till.core.Retention;
import io.till.core.Sku;
import io.till.core.Snapshot;
import io.till.core.StockItem;
import io.till.core.StockShard;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * A ledger in a few maps.
 *
 * <p>Four jobs, and only the first is obvious:
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
 *   <li>It is what a batch of commands is decided against ({@link #from}): seeded with the rows the
 *       batch read, it gives each command the snapshot the commands before it left, for that reason.
 * </ul>
 *
 * <p>Locking is coarse on purpose: one lock, held for the whole of a load and for the whole of an
 * apply, a batch's included, and never across both. That makes each half atomic and leaves the
 * interesting part — deciding against a snapshot that has since moved — exactly as exposed as it is
 * against a real database, which is the part worth testing.
 *
 * <p>Everything is kept forever. There is no compaction, no eviction of old idempotency records and
 * no bound on the outbox, because this is for tests and for single-process embedding. A long-running
 * service wants the PostgreSQL adapter and the retention settings that come with it.
 */
public final class InMemoryLedger implements Ledger, LedgerInspector, Outbox, Retention {

    private final ReentrantLock lock = new ReentrantLock();
    /** The publishing claim: held across a publish, which the lock above must never be. */
    private final ReentrantLock publishing = new ReentrantLock();

    /** Each SKU's shards, by index. */
    private final Map<Sku, List<StockShard>> stock = new LinkedHashMap<>();
    private final Map<ReservationId, Reservation> reservations = new LinkedHashMap<>();
    private final Map<IdempotencyKey, OutcomeRecord> records = new LinkedHashMap<>();
    private final TreeMap<Long, OutboxEntry> outbox = new TreeMap<>();
    private final Set<Long> published = new LinkedHashSet<>();
    private final Map<Long, Instant> publishedAt = new LinkedHashMap<>();

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
                builder.absent(sku);
                stock.getOrDefault(sku, List.of()).forEach(builder::shard);
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
        // The first by id, so that two ledgers given the same rows offer the same ones, in the same
        // order, and a differential test compares like with like: PostgreSQL orders them by id and
        // then limits them. Limiting them in the order they were taken, and sorting what was left,
        // offered different holds whenever more were standing than the limit allowed.
        found.sort(Comparator.comparing(Reservation::id));
        return found.size() > limit ? List.copyOf(found.subList(0, limit)) : found;
    }

    /**
     * A ledger holding exactly the rows a batch's snapshot read, at their versions, and nothing else:
     * what {@link io.till.core.Till#executeAll} decides a batch against (ADR 16).
     *
     * <p>Each command of the batch loads its snapshot from it, is decided, and has its decision applied
     * to it, so the next one sees what it did. A load from it answers exactly as the ledger the
     * snapshot came from did at that instant, for any command the snapshot was read for; it knows
     * nothing of any other row, so a command it was not read for is the caller's to refuse
     * ({@link BatchSnapshot#requireCovers}).
     *
     * @param snapshot what a batch read
     * @return a ledger of those rows; its outbox is empty and starts at sequence 1
     */
    public static InMemoryLedger from(BatchSnapshot snapshot) {
        InMemoryLedger seeded = new InMemoryLedger();
        // A SKU in scope with no row stays out of the map: here, as in the ledger it was read from,
        // having an entry is having a row.
        snapshot.stock().forEach((sku, shards) -> {
            if (!shards.isEmpty()) {
                seeded.stock.put(sku, new ArrayList<>(shards));
            }
        });
        seeded.reservations.putAll(snapshot.reservations());
        seeded.records.putAll(snapshot.records());
        return seeded;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Under the lock, as a {@link #load} is, so the batch's rows are one instant.
     */
    @Override
    public BatchSnapshot loadBatch(List<Command> commands) {
        lock.lock();
        try {
            Map<IdempotencyKey, OutcomeRecord> used = new LinkedHashMap<>();
            Map<ReservationId, Reservation> named = new LinkedHashMap<>();
            Set<Sku> scope = new LinkedHashSet<>();
            for (Command command : commands) {
                command.idempotencyKey()
                        .filter(records::containsKey)
                        .ifPresent(key -> used.put(key, records.get(key)));
                scope.addAll(command.declaredSkus());
                command.targetReservation().map(reservations::get).ifPresent(reservation -> {
                    named.put(reservation.id(), reservation);
                    scope.addAll(reservation.skus());
                });
            }
            Map<Sku, List<StockShard>> levels = new LinkedHashMap<>();
            for (Sku sku : scope) {
                levels.put(sku, List.copyOf(stock.getOrDefault(sku, List.of())));
            }
            return new BatchSnapshot(levels, named, used);
        } finally {
            lock.unlock();
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Under the lock, as an {@link #apply} is: every precondition checked first, then every row
     * written, so a refused batch leaves nothing behind.
     */
    @Override
    public boolean applyBatch(BatchDecision decision) {
        lock.lock();
        try {
            if (!admissible(decision)) {
                conflicts++;
                return false;
            }
            for (BatchDecision.StockWrite write : decision.stock()) {
                put(new StockShard(write.sku(), write.shard(), write.onHand(), write.reserved(), write.newVersion()));
            }
            for (Mutation.SetReservationState set : decision.states()) {
                reservations.computeIfPresent(set.reservationId(), (id, existing) -> existing.withState(set.state()));
            }
            for (Reservation reservation : decision.inserts()) {
                reservations.put(reservation.id(), reservation);
            }
            for (Event event : decision.events()) {
                long sequence = nextSequence++;
                outbox.put(sequence, new OutboxEntry(sequence, event, event.occurredAt()));
            }
            decision.records().forEach(record -> records.put(record.key(), record));
            applied += decision.outcomes().size();
            return true;
        } finally {
            lock.unlock();
        }
    }

    /** Every precondition a batch was decided under, re-checked while holding the lock. */
    private boolean admissible(BatchDecision decision) {
        for (BatchDecision.StockWrite write : decision.stock()) {
            if (versionOf(write.sku(), write.shard()) != write.expectedVersion()) {
                return false;
            }
        }
        for (Mutation.SetReservationState set : decision.states()) {
            Reservation existing = reservations.get(set.reservationId());
            if (existing == null || existing.version() != set.expectedVersion()) {
                return false;
            }
        }
        for (Reservation reservation : decision.inserts()) {
            if (reservations.containsKey(reservation.id())) {
                return false;
            }
        }
        for (OutcomeRecord record : decision.records()) {
            if (records.containsKey(record.key())) {
                return false;
            }
        }
        return true;
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
                    case Mutation.PutStock m -> put(m);
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
                        case Mutation.PutStock m -> versionOf(m.sku(), m.shard()) == m.expectedVersion();
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

    private long versionOf(Sku sku, int shard) {
        List<StockShard> shards = stock.get(sku);
        return shards != null && shard < shards.size() ? shards.get(shard).version() : StockItem.ABSENT;
    }

    private void put(Mutation.PutStock m) {
        put(new StockShard(m.sku(), m.shard(), m.onHand(), m.reserved(), m.expectedVersion() + 1));
    }

    private void put(StockShard written) {
        List<StockShard> shards = stock.computeIfAbsent(written.sku(), sku -> new ArrayList<>());
        if (written.index() < shards.size()) {
            shards.set(written.index(), written);
        } else if (written.index() == shards.size()) {
            shards.add(written);
        } else {
            // The kernel adds shards in order; one that would leave a gap is a bug, not contention.
            throw new IllegalStateException(
                    "shard " + written.index() + " of " + written.sku() + " would leave a gap after " + shards);
        }
    }

    private StockItem level(Sku sku) {
        return StockItem.of(sku, stock.get(sku));
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
    public void markPublished(List<Long> sequences, Instant at) {
        lock.lock();
        try {
            sequences.forEach(sequence -> publishedAt.put(sequence, at));
            published.addAll(sequences);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public OptionalInt publishNext(int limit, Instant at, Consumer<List<OutboxEntry>> publish) {
        if (!publishing.tryLock()) {
            return OptionalInt.empty();
        }
        try {
            List<OutboxEntry> batch = unpublished(limit);
            if (!batch.isEmpty()) {
                publish.accept(batch);
                markPublished(batch.stream().map(OutboxEntry::sequence).toList(), at);
            }
            return OptionalInt.of(batch.size());
        } finally {
            publishing.unlock();
        }
    }

    @Override
    public int forgetIdempotency(Instant before, int limit) {
        requireBatch(limit);
        lock.lock();
        try {
            // Selected and removed under one lock. Streaming the map outside it and removing inside
            // would read a collection another thread is writing, which is the bug this shape exists
            // to avoid rather than a style preference.
            // An adjustment's event is named after its idempotency key, so forgetting the key while
            // the event survives would let a later adjustment produce a key that already exists.
            Set<String> live = new HashSet<>();
            outbox.values().forEach(entry -> live.add(entry.dedupeKey()));
            List<IdempotencyKey> going =
                    records.values().stream()
                            .filter(record -> record.recordedAt().isBefore(before))
                            .filter(record -> !live.contains(Event.StockAdjusted.dedupeKeyFor(record.key())))
                            .sorted(Comparator.comparing(OutcomeRecord::recordedAt))
                            .map(OutcomeRecord::key)
                            .limit(limit)
                            .toList();
            going.forEach(records::remove);
            return going.size();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public int pruneOutbox(Instant publishedBefore, int limit) {
        requireBatch(limit);
        lock.lock();
        try {
            List<Long> going =
                    outbox.values().stream()
                            .filter(entry -> published.contains(entry.sequence()))
                            .filter(
                                    entry -> {
                                        Instant at = publishedAt.get(entry.sequence());
                                        return at != null && at.isBefore(publishedBefore);
                                    })
                            .map(OutboxEntry::sequence)
                            .limit(limit)
                            .toList();
            going.forEach(
                    sequence -> {
                        published.remove(sequence);
                        publishedAt.remove(sequence);
                        outbox.remove(sequence);
                    });
            return going.size();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public int pruneReservations(Instant expiredBefore, int limit) {
        requireBatch(limit);
        lock.lock();
        try {
            List<ReservationId> going =
                    reservations.values().stream()
                            .filter(reservation -> reservation.state().isTerminal())
                            .filter(reservation -> reservation.expiresAt().isBefore(expiredBefore))
                            .sorted(Comparator.comparing(Reservation::expiresAt))
                            .map(Reservation::id)
                            .limit(limit)
                            .toList();
            going.forEach(reservations::remove);
            return going.size();
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
            return stock.containsKey(sku) ? Optional.of(level(sku)) : Optional.empty();
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
            return stock.keySet().stream()
                    .sorted()
                    .filter(sku -> after.isEmpty() || sku.compareTo(after.get()) > 0)
                    .limit(Math.min(limit, LedgerInspector.MAX_PAGE))
                    .map(this::level)
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
            return stock.keySet().stream().sorted().map(this::level).toList();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public List<StockShard> allShards() {
        lock.lock();
        try {
            return stock.values().stream().flatMap(List::stream).sorted(StockShard.ORDER).toList();
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

    /**
     * The contract {@link Retention} states, kept here too.
     *
     * <p>Not because a caller is likely to pass zero on purpose, but because one that computed a
     * batch size and got it wrong should find out here rather than in the number of rows left.
     */
    private static void requireBatch(int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be at least 1, got " + limit);
        }
    }
}
