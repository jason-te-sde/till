package io.till.core;

import java.util.List;
import java.util.Optional;

/**
 * What the kernel decided: one answer for the caller, and everything that has to be written down for
 * that answer to be true.
 *
 * <p>This is the seam the whole design rests on. The kernel does not write; it produces a decision,
 * and a {@link Ledger} applies <b>all of it or none of it</b> in one transaction. Splitting the
 * transaction is the failure this type exists to make hard to express: a stock level lowered without
 * its reservation moving to {@code COMMITTED} is stock that has left and is still promised, and an
 * outcome recorded without the mutations beside it turns a retry into a confirmation of something
 * that never happened.
 *
 * <p>A rejection can still carry mutations. Committing a hold that ran out of time is refused, and
 * the same decision writes that hold off, because the kernel has just established that it is expired
 * and throwing that away would mean discovering it again on the next command.
 *
 * @param outcome what the caller is told
 * @param mutations row changes, to be applied in order, all or nothing
 * @param events outbox rows, written in the same transaction
 * @param outcomeRecord the idempotency record to insert, absent for a keyless command and for a
 *     replay
 */
public record Decision(
        Outcome outcome, List<Mutation> mutations, List<Event> events, Optional<OutcomeRecord> outcomeRecord) {

    public Decision {
        if (outcome == null) {
            throw new IllegalArgumentException("decision outcome must not be null");
        }
        mutations = List.copyOf(mutations);
        events = List.copyOf(events);
        if (outcomeRecord == null) {
            throw new IllegalArgumentException("decision outcomeRecord must not be null");
        }
    }

    /**
     * Whether applying this decision would change anything.
     *
     * <p>False for a replay, which is what makes replaying free: there is nothing to write, so there
     * is no version to conflict on and no transaction to open.
     *
     * @return true if there is any mutation, event, or record to write
     */
    public boolean writes() {
        return !mutations.isEmpty() || !events.isEmpty() || outcomeRecord.isPresent();
    }
}
