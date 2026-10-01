package io.till.core;

import java.time.Instant;
import java.util.List;
import java.util.OptionalInt;
import java.util.function.Consumer;

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

    /**
     * Publishes the oldest unpublished entries, if no other publisher is: reads up to {@code limit} of
     * them, hands them to {@code publish}, and marks them published at {@code at} — all under one
     * claim that one publisher at a time can hold.
     *
     * <p>One at a time, so that two publishers never send the same entries and neither overtakes the
     * other: entries leave in sequence order, which is the order a consumer of one reservation's
     * events needs. If {@code publish} throws, nothing is marked and the claim is given up, so the
     * same entries are offered again next time.
     *
     * @param limit at most how many
     * @param at when they are handed over
     * @param publish delivers them, throwing if it could not
     * @return how many were published — 0 when none were waiting — or empty when another publisher
     *     holds the claim
     */
    OptionalInt publishNext(int limit, Instant at, Consumer<List<OutboxEntry>> publish);
}
