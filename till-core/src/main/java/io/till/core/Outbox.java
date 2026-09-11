package io.till.core;

import java.time.Instant;
import java.util.List;

/**
 * The reading half of the transactional outbox.
 *
 * <p>Events are written by {@link Ledger#apply} in the same transaction as the change they describe,
 * which is what makes it impossible for one to exist without the other. Getting them out of the
 * table and into a broker is a separate job with a separate failure mode: a publisher can crash
 * between sending a message and recording that it sent it, so delivery is <b>at least once</b> and
 * every event carries a {@link Event#dedupeKey()} a consumer can use to drop the second copy.
 *
 * <p>Sequences are ascending, so a publisher makes progress in order and a consumer that cares about
 * ordering per reservation gets it for free: all of one reservation's events are written by
 * decisions that conflict with each other, so they cannot be concurrent.
 */
public interface Outbox {

    /**
     * The oldest events that have not been marked published.
     *
     * @param limit at most how many
     * @return entries in ascending sequence order
     */
    List<OutboxEntry> unpublished(int limit);

    /**
     * How many entries are waiting.
     *
     * <p>The number to put on a dashboard and alert on. A backlog that is growing means the
     * publisher is slower than the traffic or has stopped, and either way everything downstream is
     * working from a view of stock that is falling further behind.
     *
     * @return unpublished entries
     */
    long backlog();

    /**
     * Marks entries as published.
     *
     * <p>Called after delivery, which is what makes delivery at least once rather than at most once:
     * a crash between the two repeats the send, and a crash before the send repeats it as well.
     *
     * <p>The instant is supplied rather than taken from the database, so that every timestamp in the
     * ledger comes from the same clock. Retention decides what to delete by comparing these against
     * a clock reading, and a comparison across two clocks is one nobody can reason about — or write
     * a test for.
     *
     * @param sequences the sequences to mark
     * @param at when they were delivered
     */
    void markPublished(List<Long> sequences, Instant at);
}
