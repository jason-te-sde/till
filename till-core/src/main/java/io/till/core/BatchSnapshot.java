package io.till.core;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything a batch of commands needs, read at one instant: for every command, what its own
 * {@link Ledger#load} with a reclaim limit of zero would read, read once for all of them (ADR 16).
 *
 * <p>Seeded into an {@link io.till.core.mem.InMemoryLedger} with
 * {@link io.till.core.mem.InMemoryLedger#from}, it answers each command's load exactly as the ledger
 * it was read from would have at that instant, and each command decided against it sees what the
 * ones before it did.
 *
 * <p>Like a {@link Snapshot}, a SKU in scope is present even with no row, as an empty list, and with
 * every shard it has when it has any; a SKU missing from the map is an adapter bug, and a reservation
 * whose SKUs are missing is refused here with {@link IncompleteSnapshotException}, so that it cannot
 * look like a SKU that was never stocked.
 *
 * @param stock every SKU a command names or its reservation holds, each with all its shards, in index
 *     order; none for a SKU with no row
 * @param reservations every reservation a command names that exists, by id
 * @param records every idempotency record of a key a command carries that has been used, by key
 */
public record BatchSnapshot(
        Map<Sku, List<StockShard>> stock,
        Map<ReservationId, Reservation> reservations,
        Map<IdempotencyKey, OutcomeRecord> records) {

    public BatchSnapshot {
        Map<Sku, List<StockShard>> levels = new LinkedHashMap<>();
        stock.forEach((sku, shards) -> levels.put(sku, Snapshot.inOrder(sku, shards)));
        stock = Collections.unmodifiableMap(levels);
        reservations = Collections.unmodifiableMap(new LinkedHashMap<>(reservations));
        records = Collections.unmodifiableMap(new LinkedHashMap<>(records));
        for (Map.Entry<ReservationId, Reservation> entry : reservations.entrySet()) {
            Reservation reservation = entry.getValue();
            if (!reservation.id().equals(entry.getKey())) {
                throw new IllegalArgumentException("reservation " + reservation.id() + " is filed as " + entry.getKey());
            }
            for (Sku sku : reservation.skus()) {
                if (!levels.containsKey(sku)) {
                    throw new IncompleteSnapshotException("a batch's snapshot holds reservation " + reservation.id()
                            + " and not the stock of " + sku + ", which it holds units of");
                }
            }
        }
        for (Map.Entry<IdempotencyKey, OutcomeRecord> entry : records.entrySet()) {
            if (!entry.getValue().key().equals(entry.getKey())) {
                throw new IllegalArgumentException(
                        "the record of key " + entry.getValue().key() + " is filed as " + entry.getKey());
            }
        }
    }

    /**
     * Refuses a command this snapshot was not read for.
     *
     * <p>The SKUs a command names have to be here, absent or not; the SKUs of the reservation it names,
     * if that exists, are, because the constructor refuses a reservation without them. What cannot be
     * checked is a key or a reservation that was never looked up, which reads the same as one looked
     * up and not found — as it does in a {@link Snapshot}.
     *
     * @param command a command about to be decided against this snapshot
     * @throws IncompleteSnapshotException if it names a SKU this snapshot does not hold
     */
    public void requireCovers(Command command) {
        for (Sku sku : command.declaredSkus()) {
            if (!stock.containsKey(sku)) {
                throw new IncompleteSnapshotException("a batch's snapshot has no entry for sku " + sku + ", which "
                        + command + " names; the ledger must load every sku in scope, with no shards for one "
                        + "that has no row");
            }
        }
    }
}
