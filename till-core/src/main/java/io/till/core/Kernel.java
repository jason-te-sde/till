package io.till.core;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * The reservation rules, as a function.
 *
 * <p>{@link #decide} takes a {@link Snapshot}, a {@link Command} and an instant, and returns a
 * {@link Decision}. It has no fields, opens no connection, starts no thread, and never reads a clock:
 * the instant arrives as an argument, exactly like the rows do. Two calls with equal arguments return
 * equal results, on any machine, in any order, forever.
 *
 * <p>That is not purity for its own sake. It is what lets
 * {@code till-testkit} run tens of thousands of interleavings of concurrent callers, crashes and
 * clock jumps from a single integer seed, and check after <i>every</i> step that no invariant broke —
 * and then reproduce a failure exactly by rerunning that seed. Rules that live inside a transaction
 * can only be tested at the speed of a database.
 *
 * <h2>The rules</h2>
 *
 * <ul>
 *   <li><b>A hold is all or nothing.</b> A reservation over three SKUs either takes all three or
 *       takes none and reports how far short each one fell.
 *   <li><b>Availability is {@code onHand - reserved}.</b> Committing lowers both; releasing and
 *       expiring lower only {@code reserved}. There is no other way for either number to move except
 *       an explicit {@link Command.Adjust}.
 *   <li><b>A SKU's stock may be in several shards, and the answer is the SKU's.</b> A hold takes its
 *       units from one shard when one has them and from several when none does, and gives them back
 *       to the same ones; it is refused only when the SKU as a whole is short (ADR 9).
 *   <li><b>A deadline is the truth, not the stored state.</b> A hold past its deadline is expired
 *       whether or not anything has written that down, so an answer never depends on whether a
 *       background sweep happened to have run.
 *   <li><b>A key is answered once.</b> A command whose key has been seen returns the recorded
 *       outcome and writes nothing; a key seen with a different request is refused.
 * </ul>
 *
 * <h2>What it does not do</h2>
 *
 * <p>No locking, no retrying, no waiting. If two decisions were made against the same row version,
 * one of them will fail to apply and the caller decides what to do about it; {@link Till} is the
 * caller that retries. Nothing here is aware that concurrency exists, which is why there is nothing
 * here that can deadlock.
 */
public final class Kernel {

    private Kernel() {}

    /**
     * Decides what a command does.
     *
     * @param snapshot the rows the command needs, read at one instant
     * @param command what the caller wants
     * @param now the instant to decide at; the kernel never reads a clock of its own
     * @return the answer, and everything that must be written for it to be true
     * @throws IncompleteSnapshotException if the snapshot is missing a SKU the command needs
     */
    public static Decision decide(Snapshot snapshot, Command command, Instant now) {
        if (snapshot == null || command == null || now == null) {
            throw new IllegalArgumentException("snapshot, command and now are all required");
        }

        Optional<Decision> replay = replay(snapshot, command);
        if (replay.isPresent()) {
            return replay.get();
        }

        Work work = new Work(snapshot, now);
        work.reclaim(command);

        Outcome outcome =
                switch (command) {
                    case Command.Reserve c -> reserve(work, c);
                    case Command.Commit c -> commit(work, c);
                    case Command.Release c -> release(work, c);
                    case Command.Adjust c -> adjust(work, c);
                    case Command.Shard c -> shard(work, c);
                    case Command.Sweep ignored -> new Outcome.Swept(work.reclaimed());
                };

        return work.finish(command, outcome);
    }

    /**
     * Answers from the record if this key has been used.
     *
     * <p>A replay writes nothing at all — no mutations, no events, not even the expiry of a hold the
     * snapshot showed was stale. Doing otherwise would mean a retry storm from one client silently
     * bumping row versions and pushing other callers into conflict retries, and would break the one
     * sentence worth being able to say about replays: they change nothing.
     */
    private static Optional<Decision> replay(Snapshot snapshot, Command command) {
        if (snapshot.recordedOutcome().isEmpty()) {
            return Optional.empty();
        }
        OutcomeRecord record = snapshot.recordedOutcome().get();
        if (!record.fingerprint().equals(command.fingerprint())) {
            return Optional.of(
                    decisionOnly(
                            Outcome.Rejected.of(
                                    RejectionCode.IDEMPOTENCY_KEY_REUSED,
                                    "idempotency key " + record.key() + " was used at " + record.recordedAt()
                                            + " for a different request")));
        }
        return Optional.of(decisionOnly(record.outcome()));
    }

    private static Decision decisionOnly(Outcome outcome) {
        return new Decision(outcome, List.of(), List.of(), Optional.empty());
    }

    private static Outcome reserve(Work work, Command.Reserve command) {
        if (work.snapshot.reservation().isPresent()) {
            return Outcome.Rejected.of(
                    RejectionCode.RESERVATION_ID_IN_USE,
                    "reservation " + command.reservationId() + " already exists");
        }

        List<Sku> unknown = new ArrayList<>();
        List<Outcome.Shortfall> shortfalls = new ArrayList<>();
        for (Line line : command.lines()) {
            StockItem item = work.level(line.sku());
            if (!item.exists()) {
                unknown.add(line.sku());
            } else if (item.available() < line.quantity()) {
                shortfalls.add(new Outcome.Shortfall(line.sku(), line.quantity(), item.available()));
            }
        }
        if (!unknown.isEmpty()) {
            return Outcome.Rejected.of(
                    RejectionCode.UNKNOWN_SKU,
                    "no stock has ever been recorded for " + join(unknown));
        }
        if (!shortfalls.isEmpty()) {
            // Every short line, not just the first: a caller deciding whether to offer a smaller
            // basket needs all of them, and a second round trip per SKU is a second chance for the
            // numbers to move underneath it.
            return new Outcome.Rejected(
                    RejectionCode.INSUFFICIENT_STOCK, describe(shortfalls), shortfalls);
        }

        List<Allocation> allocations = new ArrayList<>();
        for (Line line : command.lines()) {
            allocations.addAll(work.take(line, command.reservationId()));
        }
        Reservation reservation =
                new Reservation(
                        command.reservationId(),
                        command.key(),
                        command.lines(),
                        allocations,
                        ReservationState.HELD,
                        work.now,
                        work.now.plus(command.ttl()),
                        0);
        work.mutations.add(new Mutation.InsertReservation(reservation));
        work.events.add(
                new Event.StockReserved(reservation.id(), reservation.lines(), reservation.expiresAt(), work.now));
        return new Outcome.Reserved(reservation.id(), reservation.lines(), reservation.expiresAt());
    }

    private static Outcome commit(Work work, Command.Commit command) {
        Reservation reservation = work.snapshot.reservation().orElse(null);
        if (reservation == null) {
            return notFound(command.reservationId());
        }
        return switch (reservation.effectiveState(work.now)) {
            case COMMITTED ->
                    Outcome.Rejected.of(
                            RejectionCode.ALREADY_COMMITTED, "reservation " + reservation.id() + " was already committed");
            case RELEASED ->
                    Outcome.Rejected.of(
                            RejectionCode.ALREADY_RELEASED, "reservation " + reservation.id() + " was already released");
            case EXPIRED -> {
                // Write it off in the same decision, but only if it has not been written off
                // already. effectiveState collapses two different situations into one answer: a
                // hold still stored as HELD whose deadline has passed, and a hold that was written
                // off some time ago. Only the first has stock to return, and expiring the second a
                // second time takes reserved below zero.
                reclaimIfStillHeld(work, reservation);
                yield Outcome.Rejected.of(
                        RejectionCode.RESERVATION_EXPIRED,
                        "reservation " + reservation.id() + " expired at " + reservation.expiresAt());
            }
            case HELD -> {
                for (Allocation allocation : reservation.allocations()) {
                    work.put(work.shard(allocation, reservation).withCommitDelta(-allocation.quantity()));
                }
                work.mutations.add(
                        new Mutation.SetReservationState(
                                reservation.id(), ReservationState.COMMITTED, reservation.version()));
                work.events.add(new Event.StockCommitted(reservation.id(), reservation.lines(), work.now));
                yield new Outcome.Committed(reservation.id(), work.now);
            }
        };
    }

    private static Outcome release(Work work, Command.Release command) {
        Reservation reservation = work.snapshot.reservation().orElse(null);
        if (reservation == null) {
            return notFound(command.reservationId());
        }
        return switch (reservation.effectiveState(work.now)) {
            // Releasing is idempotent by nature and committing is not, which is why they disagree
            // about a terminal state. Asking for a hold to be gone when it is already gone got what
            // it asked for; asking for stock to leave twice did not.
            case COMMITTED ->
                    Outcome.Rejected.of(
                            RejectionCode.ALREADY_COMMITTED,
                            "reservation " + reservation.id() + " was committed and cannot be released");
            case RELEASED -> new Outcome.Released(reservation.id(), work.now);
            case EXPIRED -> {
                reclaimIfStillHeld(work, reservation);
                yield new Outcome.Released(reservation.id(), work.now);
            }
            case HELD -> {
                for (Allocation allocation : reservation.allocations()) {
                    work.put(work.shard(allocation, reservation).withReservedDelta(-allocation.quantity()));
                }
                work.mutations.add(
                        new Mutation.SetReservationState(
                                reservation.id(), ReservationState.RELEASED, reservation.version()));
                work.events.add(new Event.StockReleased(reservation.id(), reservation.lines(), work.now));
                yield new Outcome.Released(reservation.id(), work.now);
            }
        };
    }

    private static Outcome adjust(Work work, Command.Adjust command) {
        StockItem item = work.level(command.sku());
        if (!item.exists()) {
            if (command.delta() < 0) {
                return Outcome.Rejected.of(
                        RejectionCode.UNKNOWN_SKU,
                        "cannot remove stock from " + command.sku() + ", which has no row");
            }
            work.put(new StockShard(command.sku(), 0, command.delta(), 0, StockItem.ABSENT));
            work.events.add(
                    new Event.StockAdjusted(
                            command.key(), command.sku(), command.delta(), command.delta(), 0, work.now));
            return new Outcome.Adjusted(command.sku(), command.delta(), 0);
        }

        long after = item.onHand() + command.delta();
        // One comparison covers two failures. Reserved is never negative, so an on-hand that would
        // go below zero is also below reserved; the units being removed are either not there or
        // already promised to someone.
        if (after < item.reserved()) {
            Outcome.Shortfall shortfall =
                    new Outcome.Shortfall(command.sku(), -command.delta(), item.available());
            return new Outcome.Rejected(
                    RejectionCode.INSUFFICIENT_STOCK,
                    "cannot remove " + (-command.delta()) + " from " + command.sku()
                            + ": on-hand is " + item.onHand() + " with " + item.reserved() + " reserved",
                    List.of(shortfall));
        }
        if (command.delta() > 0) {
            work.fill(command.sku(), command.delta());
        } else {
            work.drain(command.sku(), -command.delta());
        }
        StockItem updated = work.level(command.sku());
        work.events.add(
                new Event.StockAdjusted(
                        command.key(),
                        command.sku(),
                        command.delta(),
                        updated.onHand(),
                        updated.reserved(),
                        work.now));
        return new Outcome.Adjusted(command.sku(), updated.onHand(), updated.reserved());
    }

    /**
     * Splits a SKU across at least as many shards as asked, dealing its unreserved units out evenly.
     *
     * <p>Every shard keeps what its holds have reserved, because those units are promised from there;
     * what is available is dealt out anew, over the old shards and the new ones alike. So every shard
     * of a SKU just split can take a hold, whichever one a reservation id points at.
     *
     * <p>No event: the SKU's on-hand and reserved are what they were, and nothing outside the ledger
     * has any business knowing which row they are in.
     */
    private static Outcome shard(Work work, Command.Shard command) {
        List<StockShard> shards = work.shards(command.sku());
        if (shards.isEmpty()) {
            return Outcome.Rejected.of(
                    RejectionCode.UNKNOWN_SKU, "cannot split " + command.sku() + ", which has no row");
        }
        if (shards.size() >= command.shards()) {
            return new Outcome.Sharded(command.sku(), shards.size());
        }
        long available = 0;
        for (StockShard shard : shards) {
            available += shard.available();
        }
        int count = command.shards();
        for (int index = 0; index < count; index++) {
            StockShard current = index < shards.size() ? shards.get(index) : StockShard.empty(command.sku(), index);
            long share = available / count + (index < available % count ? 1 : 0);
            work.put(new StockShard(
                    command.sku(), index, current.reserved() + share, current.reserved(), current.version()));
        }
        return new Outcome.Sharded(command.sku(), count);
    }

    /**
     * Returns an expired hold's stock if nobody has done it yet.
     *
     * <p>{@link Reservation#effectiveState} answers "is this hold still good", which is the right
     * question for deciding the command and the wrong one for deciding what to write: it says
     * {@code EXPIRED} both for a hold still stored as {@code HELD} whose deadline has passed, and for
     * one that was written off an hour ago. Only the first still has stock to give back.
     */
    private static void reclaimIfStillHeld(Work work, Reservation reservation) {
        if (reservation.state() == ReservationState.HELD) {
            work.expire(reservation);
        }
    }

    private static Outcome notFound(ReservationId id) {
        return Outcome.Rejected.of(RejectionCode.RESERVATION_NOT_FOUND, "no reservation " + id);
    }

    private static String describe(List<Outcome.Shortfall> shortfalls) {
        StringBuilder sb = new StringBuilder("not enough stock for ");
        for (int i = 0; i < shortfalls.size(); i++) {
            Outcome.Shortfall s = shortfalls.get(i);
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(s.sku()).append(" (wanted ").append(s.requested()).append(", have ").append(s.available()).append(')');
        }
        return sb.toString();
    }

    private static String join(List<Sku> skus) {
        StringBuilder sb = new StringBuilder();
        for (Sku sku : skus) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(sku);
        }
        return sb.toString();
    }

    /**
     * The mutable half of one decision.
     *
     * <p>Levels are edited in a working copy and written out once at the end, so a shard touched twice
     * in one decision — reclaimed from an expired hold and then reserved again — produces one row
     * change carrying the version the snapshot had, not two that would conflict with each other.
     */
    private static final class Work {

        private final Snapshot snapshot;
        private final Instant now;
        /** The shards of each SKU the decision has touched, as it has left them so far. */
        private final Map<Sku, List<StockShard>> levels = new LinkedHashMap<>();
        private final List<Mutation> mutations = new ArrayList<>();
        private final List<Event> events = new ArrayList<>();
        private int reclaimed;

        private Work(Snapshot snapshot, Instant now) {
            this.snapshot = snapshot;
            this.now = now;
        }

        private List<StockShard> shards(Sku sku) {
            List<StockShard> working = levels.get(sku);
            return working != null ? working : snapshot.shards(sku);
        }

        /** A SKU's level: its shards as the decision has left them, added up. */
        private StockItem level(Sku sku) {
            return StockItem.of(sku, shards(sku));
        }

        /** The shard an allocation of this reservation came from. */
        private StockShard shard(Allocation allocation, Reservation reservation) {
            List<StockShard> shards;
            try {
                shards = shards(allocation.sku());
            } catch (IncompleteSnapshotException e) {
                throw new IncompleteSnapshotException(e.getMessage() + "; needed by reservation " + reservation.id());
            }
            if (allocation.shard() >= shards.size()) {
                throw new IncompleteSnapshotException("reservation " + reservation.id() + " holds " + allocation
                        + ", and the snapshot has " + shards.size() + " shards of " + allocation.sku());
            }
            return shards.get(allocation.shard());
        }

        private void put(StockShard shard) {
            List<StockShard> working =
                    levels.computeIfAbsent(shard.sku(), sku -> new ArrayList<>(snapshot.shards(sku)));
            if (shard.index() < working.size()) {
                working.set(shard.index(), shard);
            } else if (shard.index() == working.size()) {
                working.add(shard);
            } else {
                throw new IllegalStateException("shard " + shard + " would leave a gap after " + working);
            }
        }

        /**
         * Takes a line's units, from one shard if one has them all and from several if not, starting
         * at the shard the reservation id points at. The caller has established that the SKU as a
         * whole has them.
         */
        private List<Allocation> take(Line line, ReservationId id) {
            int count = shards(line.sku()).size();
            int start = Math.floorMod(spread(id), count);
            for (int i = 0; i < count; i++) {
                StockShard shard = shards(line.sku()).get((start + i) % count);
                if (shard.available() >= line.quantity()) {
                    put(shard.withReservedDelta(line.quantity()));
                    return List.of(new Allocation(line.sku(), shard.index(), line.quantity()));
                }
            }
            List<Allocation> taken = new ArrayList<>();
            long left = line.quantity();
            for (int i = 0; i < count && left > 0; i++) {
                StockShard shard = shards(line.sku()).get((start + i) % count);
                long units = Math.min(left, shard.available());
                if (units > 0) {
                    put(shard.withReservedDelta(units));
                    taken.add(new Allocation(line.sku(), shard.index(), units));
                    left -= units;
                }
            }
            if (left > 0) {
                throw new IllegalStateException(
                        "took " + (line.quantity() - left) + " of " + line + " with the SKU's total checked first: "
                                + shards(line.sku()));
            }
            return taken;
        }

        /**
         * Where a reservation starts looking: its id's hash, with the high bits folded in, so that ids
         * that differ only at the end still spread. {@link String#hashCode} is specified, so this is the
         * same on every machine and a replay of a decision takes the same shards.
         */
        private static int spread(ReservationId id) {
            int hash = id.value().hashCode();
            return hash ^ (hash >>> 16);
        }

        /** Adds units to the shards with the least available first, levelling them. */
        private void fill(Sku sku, long units) {
            List<StockShard> shards = shards(sku);
            long[] share = level(shards, units, true);
            for (int i = 0; i < share.length; i++) {
                if (share[i] > 0) {
                    put(shards(sku).get(i).withOnHandDelta(share[i]));
                }
            }
        }

        /** Takes unreserved units from the shards with the most available first, levelling them. */
        private void drain(Sku sku, long units) {
            List<StockShard> shards = shards(sku);
            long[] share = level(shards, units, false);
            for (int i = 0; i < share.length; i++) {
                if (share[i] > 0) {
                    put(shards(sku).get(i).withOnHandDelta(-share[i]));
                }
            }
        }

        /**
         * How many units each shard gets, or gives, so that the ones with the least available (or the
         * most) end as level as whole units allow: water poured in, or let out. The caller has checked
         * that there is enough to let out.
         *
         * @return per shard, by index, a count never negative
         */
        private static long[] level(List<StockShard> shards, long units, boolean pour) {
            int count = shards.size();
            Integer[] order = new Integer[count];
            for (int i = 0; i < count; i++) {
                order[i] = i;
            }
            // Lowest available first when pouring, highest first when letting out; by index on a tie.
            Arrays.sort(order, Comparator.<Integer>comparingLong(
                            i -> pour ? shards.get(i).available() : -shards.get(i).available())
                    .thenComparingInt(i -> i));
            // Letting out is pouring into the negated levels: the fullest shard is the lowest there.
            long[] height = new long[count];
            int[] rank = new int[count];
            for (int i = 0; i < count; i++) {
                long available = shards.get(order[i]).available();
                height[i] = pour ? available : -available;
                rank[order[i]] = i;
            }
            // The first k shards reach the level of the next one while that costs at most what there is.
            int k = 1;
            long sum = height[0];
            while (k < count && (long) k * height[k] - sum <= units) {
                sum += height[k];
                k++;
            }
            long target = Math.floorDiv(sum + units, k);
            long extra = (sum + units) - target * k;
            long[] share = new long[count];
            // The units that do not divide evenly go to the lowest-numbered of the shards at the level.
            Integer[] pool = Arrays.copyOf(order, k);
            Arrays.sort(pool);
            for (int index : pool) {
                long end = target + (extra-- > 0 ? 1 : 0);
                share[index] = end - height[rank[index]];
            }
            return share;
        }

        /**
         * Writes off held reservations whose deadline has passed.
         *
         * <p>Runs before every command, not only before a sweep. A reservation that fails for want
         * of stock while an expired hold still counts against it would be wrong, and asking the
         * caller to run a sweep first would make the answer depend on operational luck.
         */
        private void reclaim(Command command) {
            List<Reservation> candidates = snapshot.reclaimable();
            int budget = command instanceof Command.Sweep sweep ? sweep.limit() : Integer.MAX_VALUE;

            Set<ReservationId> done = new HashSet<>();
            // The command's own reservation is left to the command, which knows whether expiring it
            // is a refusal (commit) or a success (release) and has to say so in the outcome.
            snapshot.reservation().map(Reservation::id).ifPresent(done::add);

            List<Reservation> ordered = new ArrayList<>(candidates);
            ordered.sort(Comparator.comparing(Reservation::id));
            for (Reservation reservation : ordered) {
                if (reclaimed >= budget) {
                    break;
                }
                if (!done.add(reservation.id()) || !reservation.isReclaimableAt(now)) {
                    continue;
                }
                expire(reservation);
            }
        }

        private void expire(Reservation reservation) {
            if (reservation.state() != ReservationState.HELD) {
                // Every caller has already established this, and the one that stopped doing so cost
                // a simulator run to find. Stating it here means the next one fails at the line that
                // is wrong rather than inside an arithmetic check three frames down.
                throw new IllegalStateException(
                        "only a held reservation has stock to return, and " + reservation.id() + " is "
                                + reservation.state());
            }
            for (Allocation allocation : reservation.allocations()) {
                put(shard(allocation, reservation).withReservedDelta(-allocation.quantity()));
            }
            mutations.add(
                    new Mutation.SetReservationState(
                            reservation.id(), ReservationState.EXPIRED, reservation.version()));
            events.add(new Event.StockExpired(reservation.id(), reservation.lines(), now));
            reclaimed++;
        }

        private int reclaimed() {
            return reclaimed;
        }

        /**
         * Turns the working state into a decision: the stock rows that changed, in SKU and shard
         * order, then the reservations, and the idempotency record if the command had a key.
         *
         * <p>Stock first, because a stock row is the row most likely to have moved since the
         * snapshot — every command on its shard writes it. A ledger that applies the mutations in order
         * finds that out before it has written anything else, rather than after inserting a
         * reservation and its lines only to roll them back, which is what 64% of the reservations
         * the fourth load test inserted came to (docs/load-test.md). A shard the decision creates is
         * written even at zero, because a SKU has as many shards as it has rows.
         */
        private Decision finish(Command command, Outcome outcome) {
            List<Mutation> all = new ArrayList<>();
            for (Map.Entry<Sku, List<StockShard>> entry : new TreeMap<>(levels).entrySet()) {
                List<StockShard> before = snapshot.shards(entry.getKey());
                for (StockShard after : entry.getValue()) {
                    StockShard was = after.index() < before.size() ? before.get(after.index()) : null;
                    if (was == null) {
                        all.add(new Mutation.PutStock(
                                after.sku(), after.index(), after.onHand(), after.reserved(), StockItem.ABSENT));
                    } else if (was.onHand() != after.onHand() || was.reserved() != after.reserved()) {
                        all.add(new Mutation.PutStock(
                                after.sku(), after.index(), after.onHand(), after.reserved(), was.version()));
                    }
                }
            }
            all.addAll(mutations);
            Optional<OutcomeRecord> record =
                    command.idempotencyKey().isPresent()
                            ? Optional.of(OutcomeRecord.of(command, outcome, now))
                            : Optional.empty();
            return new Decision(outcome, all, events, record);
        }
    }
}
