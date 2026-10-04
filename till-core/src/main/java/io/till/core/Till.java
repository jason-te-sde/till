package io.till.core;

import io.till.core.mem.InMemoryLedger;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * <p>{@link #executeAll} takes several commands through the same loop at once: one load for all of
 * them, a decision for each against what the ones before it did, and one write (ADR 16). The answers
 * are the ones they would have had taken through it one at a time, in the order given. The commands
 * are decided against an {@link InMemoryLedger} seeded with what the batch loaded, which is the
 * ledger the PostgreSQL adapter is differentially tested against, rather than against a second
 * implementation of what a ledger does with a decision.
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
     * Runs a command to completion, for a caller that will wait however long it takes.
     *
     * @param command what to do
     * @return what happened, including a rejection if it was refused
     * @throws ConflictException if other callers kept winning the race for the rows
     * @throws IncompleteSnapshotException if the ledger did not load what the command needed
     */
    public Outcome execute(Command command) {
        return execute(command, null);
    }

    /**
     * Runs a command to completion, but never for a caller that has stopped waiting for it.
     *
     * <p>The deadline is checked twice an attempt: before the load, so a database already behind
     * does not do a read for nobody, and again after deciding but before {@code apply}, so a
     * decision is never written for a caller who is no longer there to hear it. Both checks use the
     * same clock {@link #execute(Command)} does, so a caller embedding the kernel sees one notion of
     * "now" throughout.
     *
     * <p>What this does not bound is {@code apply} itself: a decision can still be applied after the
     * deadline if applying waits, for a pooled connection or for its statements. That gap is measured
     * rather than closed here — see {@code till.late} in the server module — because closing it would
     * mean the {@link Ledger} checking the deadline inside its write transaction, which is a cost on
     * every command for a case this service's load tests have not yet shown to be common.
     *
     * @param command what to do
     * @param deadline the instant, on this till's own clock, after which the caller is no longer
     *     waiting; null for no deadline, which is what {@link #execute(Command)} passes
     * @return what happened, including a rejection if it was refused
     * @throws DeadlineExceededException if the deadline had already passed before the load, or
     *     passed between deciding and applying
     * @throws ConflictException if other callers kept winning the race for the rows
     * @throws IncompleteSnapshotException if the ledger did not load what the command needed
     */
    public Outcome execute(Command command, Instant deadline) {
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            // Truncated to microseconds because that is the resolution PostgreSQL stores, and an
            // instant that loses precision on the way to disk is an instant that comes back
            // different. A deadline that moves by 400 nanoseconds between writing and reading is
            // harmless; a recorded outcome that no longer equals the one that was returned is not.
            Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
            if (deadline != null && !now.isBefore(deadline)) {
                throw new DeadlineExceededException(command, deadline);
            }
            Decision decision = decide(command, now);

            // A replay, or a sweep that found nothing. There is nothing to write, so there is
            // nothing to conflict on, no transaction worth opening, and nothing the caller could
            // be too late for.
            if (!decision.writes()) {
                return decision.outcome();
            }
            // The clock is read again only when there is a deadline to read it for: a stepping
            // clock in a test or the simulator sees exactly the reads it saw before deadlines
            // existed.
            if (deadline != null && !clock.instant().truncatedTo(ChronoUnit.MICROS).isBefore(deadline)) {
                throw new DeadlineExceededException(command, deadline);
            }
            if (ledger.apply(decision)) {
                return decision.outcome();
            }
            LOG.debug("conflict applying {} on attempt {} of {}", command, attempt, maxAttempts);
        }
        throw new ConflictException(command, maxAttempts);
    }

    /**
     * Runs commands that are waiting at the same time together, with exactly the answers running them
     * one at a time, in the order given, would have given (ADR 16).
     *
     * <p>The commands are taken in runs. For a run, the clock is read once, and a command whose
     * deadline has already passed is answered {@link DeadlineExceededException} and left out; the rest
     * are loaded in one {@link Ledger#loadBatch}, decided one after another against an
     * {@link InMemoryLedger} holding exactly what was loaded — each against what the ones before it
     * did, through the same steps as {@link #execute} — and written in one {@link Ledger#applyBatch}.
     * A refused write loads the run again and decides it again, with no wait, up to the till's
     * attempts; when they run out, every command of the run that wrote something is answered
     * {@link ConflictException}, and a replay, which wrote nothing, its outcome.
     *
     * <p>Before the write the clock is read again, if any command of the run has a deadline, and a
     * command whose deadline has passed since is answered {@link DeadlineExceededException} and the run
     * decided again without it, from the same snapshot: never written for a caller who has gone, and
     * the commands after it decided as if it had never been there, as one at a time would have.
     *
     * <p>Two kinds of command end a run where they stand and go through {@link #execute} on their own:
     * one whose decision is short of stock, while expired holds may be written off — {@code execute}
     * loads them and decides again, and a batch's snapshot does not have them — and a sweep, which
     * loads what it finds rather than what it names. The next run starts after it, so the answers stay
     * those of the order given.
     *
     * <p>A failure that is not a refusal is the answer of every command it reached: a load that throws,
     * of every command of its run; a write that throws, of every command whose decision it was writing.
     * A command's own decision throwing is that command's answer alone.
     *
     * @param calls the commands, each with its caller's deadline or none, in the order they arrived
     * @return an answer for each, in the same order, and how many of the batch's writes were refused
     */
    public BatchResult executeAll(List<Call> calls) {
        if (calls == null) {
            throw new IllegalArgumentException("calls are required");
        }
        Answer[] answers = new Answer[calls.size()];
        int conflicts = 0;
        int next = 0;
        while (next < calls.size()) {
            Run run = run(calls, next, answers);
            conflicts += run.conflicts();
            next = run.end();
            if (next < calls.size()) {
                // The run stopped at a command that goes on its own: a shortfall, or a sweep.
                answers[next] = alone(calls.get(next));
                next++;
            }
        }
        return new BatchResult(List.of(answers), conflicts);
    }

    /**
     * Decides and writes the run of commands that starts at {@code start}, which ends before the first
     * sweep, or before the first command whose decision is short of stock, or at the end.
     */
    private Run run(List<Call> calls, int start, Answer[] answers) {
        int limit = start;
        while (limit < calls.size() && !(calls.get(limit).command() instanceof Command.Sweep)) {
            limit++;
        }
        int conflicts = 0;
        for (int attempt = 1; ; attempt++) {
            Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
            List<Integer> live = new ArrayList<>();
            for (int index = start; index < limit; index++) {
                Call call = calls.get(index);
                if (answers[index] != null) {
                    continue;
                }
                if (call.deadline() != null && !now.isBefore(call.deadline())) {
                    answers[index] = Answer.failed(new DeadlineExceededException(call.command(), call.deadline()));
                } else {
                    live.add(index);
                }
            }
            if (live.isEmpty()) {
                return new Run(limit, conflicts);
            }

            BatchSnapshot snapshot;
            try {
                snapshot = ledger.loadBatch(live.stream().map(index -> calls.get(index).command()).toList());
            } catch (RuntimeException e) {
                live.forEach(index -> answers[index] = Answer.failed(e));
                return new Run(limit, conflicts);
            }
            Decided decided = decideInTime(snapshot, calls, live, now, answers);
            int end = decided.cut() != null ? decided.cut() : limit;

            boolean written;
            try {
                BatchDecision batch = BatchDecision.of(decided.decisions());
                written = !batch.writes() || ledger.applyBatch(batch);
            } catch (RuntimeException e) {
                decided.answers().forEach((index, answer) ->
                        answers[index] = decided.writers().contains(index) ? Answer.failed(e) : answer);
                return new Run(end, conflicts);
            }
            if (written) {
                decided.answers().forEach((index, answer) -> answers[index] = answer);
                return new Run(end, conflicts);
            }
            conflicts++;
            LOG.debug("conflict writing a batch of {} on attempt {} of {}", decided.decisions().size(), attempt, maxAttempts);
            if (attempt >= maxAttempts) {
                decided.answers().forEach((index, answer) -> answers[index] = decided.writers().contains(index)
                        ? Answer.failed(new ConflictException(calls.get(index).command(), maxAttempts))
                        : answer);
                return new Run(end, conflicts);
            }
        }
    }

    /**
     * Decides a run against its snapshot, and decides it again without any command whose deadline
     * passed while it was loaded and decided. Each round takes one or more commands out, so it ends.
     */
    private Decided decideInTime(BatchSnapshot snapshot, List<Call> calls, List<Integer> live, Instant now, Answer[] answers) {
        List<Integer> remaining = new ArrayList<>(live);
        while (true) {
            Decided decided = decide(snapshot, calls, remaining, now);
            // The clock is read again only when there is a deadline to read it for, as in execute.
            if (decided.writers().stream().allMatch(index -> calls.get(index).deadline() == null)) {
                return decided;
            }
            Instant at = clock.instant().truncatedTo(ChronoUnit.MICROS);
            List<Integer> gone = decided.writers().stream()
                    .filter(index -> calls.get(index).deadline() != null && !at.isBefore(calls.get(index).deadline()))
                    .toList();
            if (gone.isEmpty()) {
                return decided;
            }
            for (int index : gone) {
                Call call = calls.get(index);
                answers[index] = Answer.failed(new DeadlineExceededException(call.command(), call.deadline()));
            }
            remaining.removeAll(gone);
        }
    }

    /**
     * Takes each command through the loop's steps against a ledger holding exactly the batch's
     * snapshot — load, decide, apply — so that each is decided against what the ones before it did,
     * and stops at the first whose decision is short of stock while expired holds may be written off.
     */
    private Decided decide(BatchSnapshot snapshot, List<Call> calls, List<Integer> indices, Instant now) {
        InMemoryLedger scratch = InMemoryLedger.from(snapshot);
        Map<Integer, Answer> answers = new LinkedHashMap<>();
        Set<Integer> writers = new HashSet<>();
        List<Decision> decisions = new ArrayList<>();
        for (int index : indices) {
            Command command = calls.get(index).command();
            Decision decision;
            try {
                snapshot.requireCovers(command);
                decision = Kernel.decide(scratch.load(command, now, 0), command, now);
            } catch (RuntimeException e) {
                // This command's alone, as it would be one at a time; the scratch is as it was.
                answers.put(index, Answer.failed(e));
                continue;
            }
            if (needsReclaim(decision)) {
                return new Decided(answers, writers, decisions, index);
            }
            if (decision.writes()) {
                if (!scratch.apply(decision)) {
                    // Nothing else writes to the scratch, so a refusal here is a bug, not contention.
                    answers.put(index, Answer.failed(new IllegalStateException(
                            "a decision made against a batch's own ledger was refused by it: " + command)));
                    continue;
                }
                writers.add(index);
                decisions.add(decision);
            }
            answers.put(index, Answer.of(decision.outcome()));
        }
        return new Decided(answers, writers, decisions, null);
    }

    /** Runs a command on its own, through {@link #execute}, and keeps whatever it throws as its answer. */
    private Answer alone(Call call) {
        try {
            return Answer.of(execute(call.command(), call.deadline()));
        } catch (RuntimeException e) {
            return Answer.failed(e);
        }
    }

    /**
     * Whether a decision made without the expired holds is one they could change: a shortfall, while
     * this till writes them off.
     */
    private boolean needsReclaim(Decision lean) {
        return reclaimLimit > 0
                && lean.outcome() instanceof Outcome.Rejected rejected
                && rejected.code() == RejectionCode.INSUFFICIENT_STOCK;
    }

    /** Where a run stopped — the index of the first command it did not answer, or the end — and its refused writes. */
    private record Run(int end, int conflicts) {}

    /**
     * A run, decided: each command's answer if the write goes through, which of them wrote, their
     * decisions in order, and where the run was cut short, if it was.
     */
    private record Decided(Map<Integer, Answer> answers, Set<Integer> writers, List<Decision> decisions, Integer cut) {}

    /**
     * One command of a batch, and when its caller stops waiting for it.
     *
     * @param command what to do
     * @param deadline the instant, on the till's own clock, after which the caller is no longer
     *     waiting; null for none
     */
    public record Call(Command command, Instant deadline) {

        public Call {
            if (command == null) {
                throw new IllegalArgumentException("a call needs a command");
            }
        }

        /**
         * A command whose caller will wait however long it takes.
         *
         * @param command what to do
         * @return the call, with no deadline
         */
        public static Call of(Command command) {
            return new Call(command, null);
        }
    }

    /**
     * What one command of a batch came to: an outcome, rejections included, or the exception
     * {@link #execute} would have thrown for it. Exactly one of the two.
     *
     * @param outcome what happened, or null if it failed
     * @param failure why it failed, or null if it has an outcome
     */
    public record Answer(Outcome outcome, RuntimeException failure) {

        public Answer {
            if ((outcome == null) == (failure == null)) {
                throw new IllegalArgumentException("an answer is an outcome or a failure, exactly one of them");
            }
        }

        /**
         * An answer with an outcome.
         *
         * @param outcome what happened
         * @return the answer
         */
        public static Answer of(Outcome outcome) {
            return new Answer(outcome, null);
        }

        /**
         * An answer that is a failure.
         *
         * @param failure what {@link #execute} would have thrown
         * @return the answer
         */
        public static Answer failed(RuntimeException failure) {
            return new Answer(null, failure);
        }

        /**
         * The outcome, as {@link #execute} would have returned it, or its failure, thrown as it would
         * have thrown it.
         *
         * @return the outcome
         */
        public Outcome get() {
            if (failure != null) {
                throw failure;
            }
            return outcome;
        }
    }

    /**
     * What a batch came to.
     *
     * @param answers one for each command, in the order they were given
     * @param conflicts how many of the batch's writes were refused because a row had moved, each of
     *     which loaded its run again
     */
    public record BatchResult(List<Answer> answers, int conflicts) {

        public BatchResult {
            answers = List.copyOf(answers);
        }
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
        Decision lean = Kernel.decide(ledger.load(command, now, 0), command, now);
        if (needsReclaim(lean)) {
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
