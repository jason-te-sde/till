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

    /** Expired holds a command short of stock will write off first, unless configured otherwise. */
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
            Decision decision = decide(command, now);

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
     * Decides without the expired holds first, and again with them only if they could change the
     * answer.
     *
     * <p>A hold whose deadline has passed still counts against its stock until something writes it
     * off, and a reservation refused for want of stock while one does would be wrong — so the kernel
     * writes off whatever expired holds the snapshot brings, before every command. Bringing them to
     * every command was the expensive part. Under load the commands on one SKU all found the same
     * expired holds, all tried to write them off, and all but one lost the race and started again:
     * the first load tests spent most of the database's time on exactly that.
     *
     * <p>So a command is decided on a snapshot without them. When that decision is anything but a
     * refusal for want of stock, the expired holds could not have changed it, and it stands; they
     * are left to the sweeper. Only a shortfall loads them and decides again — the one case they can
     * change, and a rare one while there is stock to sell.
     */
    private Decision decide(Command command, Instant now) {
        if (command instanceof Command.Sweep sweep) {
            // As many as the sweep may write off: its own limit, not a passing command's. Loading
            // the passing command's limit capped every sweep at 32 whatever its batch, and at none
            // with reclaiming turned off — which is when it is the only thing left doing it.
            return Kernel.decide(ledger.load(command, now, sweep.limit()), command, now);
        }
        if (reclaimLimit == 0) {
            return Kernel.decide(ledger.load(command, now, 0), command, now);
        }
        Decision lean = Kernel.decide(ledger.load(command, now, 0), command, now);
        if (lean.outcome() instanceof Outcome.Rejected rejected && rejected.code() == RejectionCode.INSUFFICIENT_STOCK) {
            return Kernel.decide(ledger.load(command, now, reclaimLimit), command, now);
        }
        return lean;
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
     * Splits a SKU's stock across at least {@code shards} rows, for a SKU about to be busy (ADR 9).
     *
     * @param key the caller's key for this attempt
     * @param sku which SKU
     * @param shards how many rows at least
     * @return {@link Outcome.Sharded}, or a rejection
     */
    public Outcome shard(IdempotencyKey key, Sku sku, int shards) {
        return execute(new Command.Shard(key, sku, shards));
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
         * Sets how many expired holds a command that would otherwise be short of stock writes off
         * first. A command with stock to spare writes off none: see {@code decide}.
         *
         * <p>Higher lets a short command find more of the stock that expired holds are sitting on,
         * and makes that decision touch more rows. Zero turns the behaviour off and leaves
         * reclaiming entirely to {@link Till#sweep}.
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
