package io.till.core;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The thing a caller actually uses: a {@link Ledger}, a clock, and the loop that puts them together.
 *
 * <pre>{@code
 * Till till = Till.on(new InMemoryLedger());
 * till.adjust(IdempotencyKey.of("delivery-41"), Sku.of("widget"), 100);
 *
 * Outcome outcome = till.reserve(
 *         IdempotencyKey.of("checkout-8123"),
 *         ReservationId.random(),
 *         List.of(Line.of("widget", 2)),
 *         Duration.ofMinutes(15));
 * }</pre>
 *
 * <p>The loop is four lines and every one of them is load-bearing: read a snapshot, decide against
 * it, try to apply, and start over if someone else got there first. Optimistic concurrency rather
 * than a lock, because the contended case in a flash sale is thousands of callers on one SKU, and a
 * lock turns that into a queue whose length is the latency of everyone in it.
 *
 * <p>A retry is not a delay here. There is no sleep and no backoff: a conflict means the row moved,
 * which means someone else's transaction committed, which means progress was made by somebody. Where
 * backoff belongs is in the caller that catches {@link ConflictException}, and what belongs in front
 * of this is a bounded pool, so that contention shows up as queueing rather than as a thousand
 * threads all retrying.
 *
 * <p>Instances are immutable and safe to share between threads; whether concurrent commands are safe
 * is a question about the {@link Ledger}, and the two shipped with till both are.
 */
public final class Till {

    private static final Logger LOG = LoggerFactory.getLogger(Till.class);

    /** Attempts before a command gives up, unless configured otherwise. */
    public static final int DEFAULT_MAX_ATTEMPTS = 8;

    /** Expired holds a command will write off while it is passing, unless configured otherwise. */
    public static final int DEFAULT_RECLAIM_LIMIT = 32;

    private final Ledger ledger;
    private final Clock clock;
    private final int maxAttempts;
    private final int reclaimLimit;

    private Till(Ledger ledger, Clock clock, int maxAttempts, int reclaimLimit) {
        if (ledger == null || clock == null) {
            throw new IllegalArgumentException("ledger and clock are both required");
        }
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be at least 1, got " + maxAttempts);
        }
        if (reclaimLimit < 0) {
            throw new IllegalArgumentException("reclaimLimit must not be negative, got " + reclaimLimit);
        }
        this.ledger = ledger;
        this.clock = clock;
        this.maxAttempts = maxAttempts;
        this.reclaimLimit = reclaimLimit;
    }

    /**
     * A till on a ledger, using the system clock and the default limits.
     *
     * @param ledger where the rows live
     * @return the till
     */
    public static Till on(Ledger ledger) {
        return new Till(ledger, Clock.systemUTC(), DEFAULT_MAX_ATTEMPTS, DEFAULT_RECLAIM_LIMIT);
    }

    /**
     * Starts configuring a till.
     *
     * @param ledger where the rows live
     * @return a builder
     */
    public static Builder builder(Ledger ledger) {
        return new Builder(ledger);
    }

    /**
     * Runs a command to completion.
     *
     * @param command what to do
     * @return what happened, including a rejection if it was refused
     * @throws ConflictException if other callers kept winning the race for the rows
     * @throws IncompleteSnapshotException if the ledger did not load what the command needed
     */
    public Outcome execute(Command command) {
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            // Truncated to microseconds because that is the resolution PostgreSQL stores, and an
            // instant that loses precision on the way to disk is an instant that comes back
            // different. A deadline that moves by 400 nanoseconds between writing and reading is
            // harmless; a recorded outcome that no longer equals the one that was returned is not.
            Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
            Snapshot snapshot = ledger.load(command, now, reclaimLimit);
            Decision decision = Kernel.decide(snapshot, command, now);

            // A replay, or a sweep that found nothing. There is nothing to write, so there is
            // nothing to conflict on and no transaction worth opening.
            if (!decision.writes()) {
                return decision.outcome();
            }
            if (ledger.apply(decision)) {
                return decision.outcome();
            }
            LOG.debug("conflict applying {} on attempt {} of {}", command, attempt, maxAttempts);
        }
        throw new ConflictException(command, maxAttempts);
    }

    /**
     * Takes a hold on stock.
     *
     * @param key the caller's key for this attempt
     * @param id the identifier the hold will have
     * @param lines what to hold
     * @param ttl how long the hold lasts
     * @return {@link Outcome.Reserved}, or a rejection
     */
    public Outcome reserve(IdempotencyKey key, ReservationId id, List<Line> lines, Duration ttl) {
        return execute(new Command.Reserve(key, id, lines, ttl));
    }

    /**
     * Turns a hold into a sale.
     *
     * @param key the caller's key for this attempt
     * @param id which hold
     * @return {@link Outcome.Committed}, or a rejection
     */
    public Outcome commit(IdempotencyKey key, ReservationId id) {
        return execute(new Command.Commit(key, id));
    }

    /**
     * Gives a hold back.
     *
     * @param key the caller's key for this attempt
     * @param id which hold
     * @return {@link Outcome.Released}, or a rejection
     */
    public Outcome release(IdempotencyKey key, ReservationId id) {
        return execute(new Command.Release(key, id));
    }

    /**
     * Changes on-hand stock directly.
     *
     * @param key the caller's key for this attempt
     * @param sku which SKU
     * @param delta units to add, negative to remove
     * @return {@link Outcome.Adjusted}, or a rejection
     */
    public Outcome adjust(IdempotencyKey key, Sku sku, long delta) {
        return execute(new Command.Adjust(key, sku, delta));
    }

    /**
     * Writes off holds that have run out of time.
     *
     * <p>Background work. Running it returns expired stock to {@code available} sooner than the next
     * command touching those SKUs would; not running it is safe, because a hold past its deadline
     * already counts as expired everywhere a decision is made.
     *
     * @param limit at most how many to write off
     * @return how many were written off
     */
    public int sweep(int limit) {
        Outcome outcome = execute(new Command.Sweep(limit));
        return ((Outcome.Swept) outcome).reclaimed();
    }

    /**
     * The clock this till reads.
     *
     * @return the clock
     */
    public Clock clock() {
        return clock;
    }

    /** Configures a till. */
    public static final class Builder {

        private final Ledger ledger;
        private Clock clock = Clock.systemUTC();
        private int maxAttempts = DEFAULT_MAX_ATTEMPTS;
        private int reclaimLimit = DEFAULT_RECLAIM_LIMIT;

        private Builder(Ledger ledger) {
            this.ledger = ledger;
        }

        /**
         * Sets the clock. A fixed or stepping clock makes a test's timing exact.
         *
         * @param value the clock
         * @return this builder
         */
        public Builder clock(Clock value) {
            this.clock = value;
            return this;
        }

        /**
         * Sets how many times a command is decided before it gives up.
         *
         * @param value at least 1
         * @return this builder
         */
        public Builder maxAttempts(int value) {
            this.maxAttempts = value;
            return this;
        }

        /**
         * Sets how many expired holds a passing command will write off.
         *
         * <p>Higher returns stock to {@code available} faster under load and makes each decision
         * touch more rows, which makes conflicts more likely. Zero turns the behaviour off and
         * leaves reclaiming entirely to {@link Till#sweep}.
         *
         * @param value at least 0
         * @return this builder
         */
        public Builder reclaimLimit(int value) {
            this.reclaimLimit = value;
            return this;
        }

        /**
         * Builds the till.
         *
         * @return the till
         */
        public Till build() {
            return new Till(ledger, clock, maxAttempts, reclaimLimit);
        }
    }
}
