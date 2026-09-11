package io.till.core;

import java.time.Instant;
import java.util.ArrayList;
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

        for (Line line : command.lines()) {
            work.put(work.level(line.sku()).withReservedDelta(line.quantity()));
        }
        Reservation reservation =
                new Reservation(
                        command.reservationId(),
                        command.key(),
                        command.lines(),
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
                for (Line line : reservation.lines()) {
                    work.put(work.require(line.sku(), reservation).withCommitDelta(-line.quantity()));
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
                for (Line line : reservation.lines()) {
                    work.put(work.require(line.sku(), reservation).withReservedDelta(-line.quantity()));
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
            StockItem created = new StockItem(command.sku(), command.delta(), 0, StockItem.ABSENT);
            work.put(created);
            work.events.add(
                    new Event.StockAdjusted(
                            command.key(), command.sku(), command.delta(), created.onHand(), 0, work.now));
            return new Outcome.Adjusted(command.sku(), created.onHand(), 0);
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
        StockItem updated = item.withOnHandDelta(command.delta());
        work.put(updated);
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
     * <p>Levels are edited in a working copy and written out once at the end, so a SKU touched twice
     * in one decision — reclaimed from an expired hold and then reserved again — produces one row
     * change carrying the version the snapshot had, not two that would conflict with each other.
     */
    private static final class Work {

        private final Snapshot snapshot;
        private final Instant now;
        private final Map<Sku, StockItem> levels = new LinkedHashMap<>();
        private final List<Mutation> mutations = new ArrayList<>();
        private final List<Event> events = new ArrayList<>();
        private int reclaimed;

        private Work(Snapshot snapshot, Instant now) {
            this.snapshot = snapshot;
            this.now = now;
        }

        private StockItem level(Sku sku) {
            StockItem working = levels.get(sku);
            return working != null ? working : snapshot.require(sku);
        }

        /** Like {@link #level}, with a message that names the reservation that needed the SKU. */
        private StockItem require(Sku sku, Reservation reservation) {
            try {
                return level(sku);
            } catch (IncompleteSnapshotException e) {
                throw new IncompleteSnapshotException(
                        e.getMessage() + "; needed by reservation " + reservation.id());
            }
        }

        private void put(StockItem item) {
            levels.put(item.sku(), item);
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
            for (Line line : reservation.lines()) {
                put(require(line.sku(), reservation).withReservedDelta(-line.quantity()));
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
         * Turns the working state into a decision: the stock rows that actually changed, in SKU
         * order, followed by the idempotency record if the command had a key.
         */
        private Decision finish(Command command, Outcome outcome) {
            List<Mutation> all = new ArrayList<>(mutations);
            for (Map.Entry<Sku, StockItem> entry : new TreeMap<>(levels).entrySet()) {
                StockItem before = snapshot.require(entry.getKey());
                StockItem after = entry.getValue();
                if (before.onHand() != after.onHand() || before.reserved() != after.reserved()) {
                    all.add(
                            new Mutation.PutStock(
                                    after.sku(), after.onHand(), after.reserved(), before.version()));
                }
            }
            Optional<OutcomeRecord> record =
                    command.idempotencyKey().isPresent()
                            ? Optional.of(OutcomeRecord.of(command, outcome, now))
                            : Optional.empty();
            return new Decision(outcome, all, events, record);
        }
    }
}
