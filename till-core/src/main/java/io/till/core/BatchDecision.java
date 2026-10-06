package io.till.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What a batch of commands decided, as the one write that makes all of it true (ADR 16).
 *
 * <p>The commands of a batch are decided one after another, each against the batch's snapshot as the
 * ones before it left it, so their decisions form a chain: a stock row two of them wrote is expected
 * by the second at the version the first left it at. {@link #of} folds the chain into its net
 * change, and a {@link Ledger} writes that once, with {@link Ledger#applyBatch}: each row once, at its
 * final values, checked against the version the batch read. Written that way, the rows end exactly as
 * the decisions would have left them written one at a time, their versions included.
 *
 * <p>Like a {@link Decision}, all of it or none of it: a row not at the version the batch read, or a
 * key it inserts already taken, refuses the whole batch.
 *
 * @param outcomes what each decision that writes answered, in order: the ledger's guide to whether the
 *     batch must be on disk before it returns (ADR 15)
 * @param stock each stock row the batch writes, once, in SKU and shard order — the order every
 *     decision locks rows in, so that two batches never wait for each other in a cycle
 * @param states each reservation the batch read and moved to a terminal state, against the version it
 *     read
 * @param inserts each reservation the batch created, once, in the state and at the version the batch
 *     left it in
 * @param events every event, in the order the decisions made them
 * @param records every decision's idempotency record, in order
 */
public record BatchDecision(
        List<Outcome> outcomes,
        List<BatchDecision.StockWrite> stock,
        List<Mutation.SetReservationState> states,
        List<Reservation> inserts,
        List<Event> events,
        List<OutcomeRecord> records) {

    private static final Comparator<StockWrite> ROW_ORDER =
            Comparator.comparing(StockWrite::sku).thenComparingInt(StockWrite::shard);

    public BatchDecision {
        outcomes = List.copyOf(outcomes);
        stock = List.copyOf(stock);
        states = List.copyOf(states);
        inserts = List.copyOf(inserts);
        events = List.copyOf(events);
        records = List.copyOf(records);
    }

    /**
     * One stock row, written once for a whole batch.
     *
     * <p>{@link Mutation.PutStock} with the version the row is left at, which one at a time is always
     * one past the version expected, and for a batch is one past for every decision that wrote it.
     *
     * @param sku which SKU
     * @param shard which of its shards
     * @param onHand the level to write: what the last decision to write the row left
     * @param reserved how much of it is spoken for
     * @param expectedVersion the version the batch read the row at, or {@link StockItem#ABSENT} when
     *     it did not exist and the batch creates it
     * @param newVersion the version to write: always past the one expected
     */
    public record StockWrite(Sku sku, int shard, long onHand, long reserved, long expectedVersion, long newVersion) {

        public StockWrite {
            if (sku == null) {
                throw new IllegalArgumentException("sku must not be null");
            }
            if (shard < 0) {
                throw new IllegalArgumentException("shard must not be negative, got " + shard + " for " + sku);
            }
            // The refusal PutStock makes, for the same reason: an impossible level is a bug in whatever
            // computed it, and the line that did is the line that should fail.
            if (onHand < 0 || reserved < 0 || reserved > onHand) {
                throw new IllegalArgumentException("refusing to write an impossible level for " + sku + " shard " + shard
                        + ": onHand=" + onHand + " reserved=" + reserved);
            }
            if (expectedVersion < StockItem.ABSENT) {
                throw new IllegalArgumentException("version must not be below " + StockItem.ABSENT + ", got " + expectedVersion);
            }
            if (newVersion <= expectedVersion || newVersion < 0) {
                throw new IllegalArgumentException("a write moves a version forwards: " + sku + " shard " + shard
                        + " from " + expectedVersion + " to " + newVersion);
            }
        }

        /**
         * Whether this write creates the row rather than updating it.
         *
         * @return true when the expected version is {@link StockItem#ABSENT}
         */
        public boolean isInsert() {
            return expectedVersion == StockItem.ABSENT;
        }
    }

    /**
     * Folds decisions made one after another into the write that makes all of them true.
     *
     * <p>Each decision is taken to have been made against the state the ones before it left, and the
     * fold checks that it was: a row a decision expects at a version other than the one the decisions
     * before it left it at, a reservation created twice or moved twice, a key recorded twice — each of
     * those is a batch that was not decided one command after another, and is refused here rather than
     * written. A decision that writes nothing, a replay, adds nothing.
     *
     * @param decisions the decisions, in the order they were made
     * @return their net change
     * @throws IllegalArgumentException if the decisions are not a chain
     */
    public static BatchDecision of(List<Decision> decisions) {
        Map<String, StockWrite> stock = new LinkedHashMap<>();
        Map<ReservationId, Mutation.SetReservationState> states = new LinkedHashMap<>();
        Map<ReservationId, Reservation> inserts = new LinkedHashMap<>();
        List<Outcome> outcomes = new ArrayList<>();
        List<Event> events = new ArrayList<>();
        List<OutcomeRecord> records = new ArrayList<>();
        Set<IdempotencyKey> keys = new HashSet<>();

        for (int at = 0; at < decisions.size(); at++) {
            Decision decision = decisions.get(at);
            int index = at;
            if (!decision.writes()) {
                continue;
            }
            for (Mutation mutation : decision.mutations()) {
                switch (mutation) {
                    case Mutation.PutStock put -> stock.merge(
                            put.sku() + "#" + put.shard(),
                            new StockWrite(
                                    put.sku(), put.shard(), put.onHand(), put.reserved(), put.expectedVersion(),
                                    put.expectedVersion() + 1),
                            (before, after) -> chained(before, after, index));
                    case Mutation.InsertReservation insert -> {
                        ReservationId id = insert.reservation().id();
                        if (inserts.containsKey(id) || states.containsKey(id)) {
                            throw new IllegalArgumentException(
                                    "decision " + index + " of a batch creates reservation " + id + ", which the batch "
                                            + "already has");
                        }
                        inserts.put(id, insert.reservation());
                    }
                    case Mutation.SetReservationState set -> {
                        Reservation created = inserts.get(set.reservationId());
                        if (created != null && created.version() == set.expectedVersion()
                                && created.state() == ReservationState.HELD) {
                            // Created by this batch: created in its final state instead.
                            inserts.put(set.reservationId(), created.withState(set.state()));
                        } else if (created == null && !states.containsKey(set.reservationId())) {
                            states.put(set.reservationId(), set);
                        } else {
                            throw new IllegalArgumentException(
                                    "decision " + index + " of a batch moves reservation " + set.reservationId() + " to "
                                            + set.state() + " at version " + set.expectedVersion()
                                            + ", which the decisions before it already moved");
                        }
                    }
                }
            }
            events.addAll(decision.events());
            decision.outcomeRecord().ifPresent(record -> {
                if (!keys.add(record.key())) {
                    throw new IllegalArgumentException("two decisions of a batch record key " + record.key()
                            + "; the second should have replayed the first");
                }
                records.add(record);
            });
            outcomes.add(decision.outcome());
        }

        List<StockWrite> rows = new ArrayList<>(stock.values());
        rows.sort(ROW_ORDER);
        return new BatchDecision(
                outcomes, rows, List.copyOf(states.values()), List.copyOf(inserts.values()), events, records);
    }

    /** A row written again: the first write's expectation, the second's values, one version further on. */
    private static StockWrite chained(StockWrite before, StockWrite after, int index) {
        if (after.expectedVersion() != before.newVersion()) {
            throw new IllegalArgumentException(
                    "decision " + index + " of a batch expects " + after.sku() + " shard " + after.shard() + " at version "
                            + after.expectedVersion() + ", and the decisions before it left it at "
                            + before.newVersion());
        }
        return new StockWrite(
                after.sku(), after.shard(), after.onHand(), after.reserved(), before.expectedVersion(), after.newVersion());
    }

    /**
     * Whether writing this would change anything.
     *
     * @return true if there is any row, event or record to write
     */
    public boolean writes() {
        return !stock.isEmpty() || !states.isEmpty() || !inserts.isEmpty() || !events.isEmpty() || !records.isEmpty();
    }
}
