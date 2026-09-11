package io.till.server;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * A clock a test moves by hand.
 *
 * <p>Time being a bean is what lets a test cross a hold's deadline without sleeping through it. A
 * suite that sleeps is slow when it passes and flaky when it does not, and the thing it is testing —
 * that a deadline in the past makes a hold expired — has nothing to do with how long the test took.
 */
final class MutableClock extends Clock {

    private volatile Instant now;

    MutableClock(Instant start) {
        this.now = start;
    }

    void advance(Duration by) {
        now = now.plus(by);
    }

    /**
     * Puts the clock back, so that one test advancing it does not reach the next.
     *
     * @param instant where to put it
     */
    void reset(Instant instant) {
        now = instant;
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return now;
    }
}
