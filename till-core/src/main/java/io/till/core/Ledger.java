package io.till.core;

import java.time.Instant;
import java.util.List;

/**
 * Where the rows live.
 *
 * <p>Two methods, and the whole difficulty of the project is in the contract between them rather
 * than in either signature. Two more do the same for a batch of commands (ADR 16):
 * {@link #loadBatch} reads at one instant what each of them would load, and {@link #applyBatch}
 * writes, all of it or none of it, what they decided.
 *
 * <h2>load</h2>
 *
 * <p>Must read everything the command could need, at one consistent instant — one transaction, or
 * one read under one lock. A decision assembled from rows read at two moments can be wrong in a way
 * no version check will catch, because each row individually is still at the version it was read at.
 *
 * <p>Every SKU in scope must appear in {@link Snapshot#stock()}, including SKUs with no row, as
 * {@link StockItem#empty}. Leaving one out is an adapter bug and raises
 * {@link IncompleteSnapshotException} rather than quietly becoming an "out of stock" answer.
 *
 * <h2>apply</h2>
 *
 * <p>Must write the mutations, the events and the idempotency record <b>in one transaction</b>, and
 * must refuse the whole thing if any expected version has moved, any inserted id is taken, or the
 * idempotency key was claimed by someone else in the meantime. Refusing returns {@code false}; it is
 * not an error, it is the ordinary outcome of two callers reaching the same row, and {@link Till}
 * answers it by loading again and deciding again.
 *
 * <p>The transaction is not an implementation detail. An outbox row written outside it can describe
 * a change that was rolled back; a stock level written outside it can leave stock that has left the
 * building still promised to somebody.
 */
public interface Ledger {

    /**
     * Reads what a command needs.
     *
     * @param command the command about to be decided
     * @param now the instant the decision will be made at, used to find holds past their deadline
     * @param reclaimLimit at most how many expired holds to include; a hint, and zero is legal
     * @return a consistent snapshot
     */
    Snapshot load(Command command, Instant now, int reclaimLimit);

    /**
     * Writes a decision, all of it or none of it.
     *
     * @param decision what the kernel decided
     * @return true if it was written, false if a version moved and the decision must be made again
     */
    boolean apply(Decision decision);

    /**
     * Reads what a batch of commands needs, at one consistent instant.
     *
     * <p>For each command, what {@link #load} with a reclaim limit of zero reads for it: its key's
     * record, the reservation it names with its lines, and every shard of every SKU it names or that
     * reservation holds — each row once, however many commands name it. One instant, as for
     * {@link #load}: the batch's decisions are only right together if the rows they were made from
     * were all true at once.
     *
     * <p>No expired holds, and nothing for a sweep: a command that needs those is decided on its
     * own, through {@link #load}.
     *
     * @param commands the commands about to be decided together
     * @return a consistent snapshot of what all of them need
     */
    BatchSnapshot loadBatch(List<Command> commands);

    /**
     * Writes what a batch of commands decided, all of it or none of it, in one transaction.
     *
     * <p>Refuses the whole batch, as {@link #apply} refuses a decision, if any row it writes is not at
     * the version the batch read, or any reservation, event or idempotency key it inserts is taken.
     * Each stock row is written at the version the batch says, which is as far as its decisions one at
     * a time would have moved it; each reservation it inserts, in the state and at the version it
     * says.
     *
     * @param decision the batch's decisions, folded by {@link BatchDecision#of}
     * @return true if it was written, false if something moved and the batch must be decided again
     */
    boolean applyBatch(BatchDecision decision);
}
