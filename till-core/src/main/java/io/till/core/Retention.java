package io.till.core;

import java.time.Instant;

/**
 * Deleting history that is no longer needed.
 *
 * <p>Three tables grow for as long as the service runs and none of them is pruned by the command
 * path. Left out, the alternative is a sentence in an operations guide telling somebody to write a
 * cron job — which works right up until the person who read it changes team, and then the disk
 * fills. So it is a port, with a default, rather than advice.
 *
 * <p>Every method deletes in a <b>bounded batch</b> and returns how many rows went. One statement
 * removing a month of rows holds a lock long enough to be noticed by everything else, so a caller
 * loops on a small batch until it returns less than it asked for.
 *
 * <p>A limit below one is an {@link IllegalArgumentException} in every implementation, and is part of
 * this contract rather than a habit one of them happens to have. Zero is the dangerous value: a
 * caller that computed a batch size and got it wrong means "delete nothing", and an implementation
 * that read it as "no limit" would delete everything eligible in one statement.
 *
 * <p>The dangerous one is the idempotency table. Deleting a record means a caller retrying that
 * command executes it <b>again</b>, so the cutoff has to be comfortably longer than the longest
 * client retry window — which is a fact about the callers, not about till.
 */
public interface Retention {

    /**
     * Forgets idempotency records decided before an instant.
     *
     * <p>A record whose adjustment is still sitting in the outbox is kept regardless of age. An
     * adjustment's event is named {@code adjusted:<idempotency-key>} because an adjustment has no
     * identity of its own, so that name is unique only while the record exists. Forget the record
     * early and the next execution of that command writes an event whose key is already taken: the
     * insert conflicts, the decision cannot be applied, and the caller gets 503 forever.
     *
     * @param before the cutoff; a record recorded before this may go
     * @param limit at most this many rows
     * @return how many were deleted
     * @throws IllegalArgumentException if {@code limit} is below one
     */
    int forgetIdempotency(Instant before, int limit);

    /**
     * Deletes outbox rows that were published before an instant.
     *
     * <p>Published only. An unpublished row is a change nothing downstream has heard about, and
     * deleting one loses it for good.
     *
     * @param publishedBefore the cutoff
     * @param limit at most this many rows
     * @return how many were deleted
     * @throws IllegalArgumentException if {@code limit} is below one
     */
    int pruneOutbox(Instant publishedBefore, int limit);

    /**
     * Deletes finished reservations that expired before an instant.
     *
     * <p>Finished only: a {@code HELD} reservation's units are counted in
     * {@link StockItem#reserved()}, and deleting the row without lowering that counter leaks the
     * stock permanently. Nothing here can be asked to do that.
     *
     * @param expiredBefore the cutoff
     * @param limit at most this many rows
     * @return how many were deleted, counting the reservation rather than its lines
     * @throws IllegalArgumentException if {@code limit} is below one
     */
    int pruneReservations(Instant expiredBefore, int limit);
}
