package io.till.core;

import java.time.Instant;

/**
 * One event waiting to be published.
 *
 * @param sequence the ledger's ordering, ascending and gap-free within one ledger
 * @param event what happened
 * @param recordedAt when the row was written
 */
public record OutboxEntry(long sequence, Event event, Instant recordedAt) {

    /**
     * The event's deduplication key.
     *
     * @return {@link Event#dedupeKey()}
     */
    public String dedupeKey() {
        return event.dedupeKey();
    }
}
