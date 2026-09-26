package io.till.store;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * A clock the test moves by hand.
 *
 * <p>Shared by the store and by the embedded ledger, so a hold's deadline and the store's idea of
 * "now" come from the same source and a test can step past one exactly.
 */
public final class MutableClock extends Clock {

    private volatile Instant now;

    MutableClock(Instant start) {
        this.now = start;
    }

    /**
     * @param by how far to move forward
     */
    public void advance(Duration by) {
        now = now.plus(by);
    }

    /**
     * @param to the instant to reset to
     */
    public void reset(Instant to) {
        now = to;
    }

    @Override
    public Instant instant() {
        return now;
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }
}
