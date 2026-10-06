package io.till.testkit;

import io.till.core.BatchDecision;
import io.till.core.BatchSnapshot;
import io.till.core.Command;
import io.till.core.Decision;
import io.till.core.DeadlineExceededException;
import io.till.core.IdempotencyKey;
import io.till.core.Ledger;
import io.till.core.LedgerInspector;
import io.till.core.Line;
import io.till.core.OutboxEntry;
import io.till.core.Outcome;
import io.till.core.OutcomeRecord;
import io.till.core.RejectionCode;
import io.till.core.Reservation;
import io.till.core.ReservationId;
import io.till.core.Sku;
import io.till.core.Snapshot;
import io.till.core.StockShard;
import io.till.core.Till;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.function.Supplier;

/**
 * One seeded history of commands, run twice — one command at a time through {@link Till#execute},
 * and in batches of random sizes through {@link Till#executeAll} — against two empty ledgers, with
 * every answer and every row compared (ADR 16).
 *
 * <p>The history is a function of the seed alone, so both runs are given the same commands whatever
 * either answers. It is written to reach what batching could get wrong: a key sent twice in one batch,
 * and a key used again for a different request; holds committed and released in the batch that took
 * them and in later ones; reservation ids taken twice; stock running short while expired holds sit on
 * it, so that a command has to be decided on its own; sweeps in the middle of a batch; deliveries,
 * write-offs and splits between holds; and callers whose deadline has already passed. Each batch runs
 * at one instant, its own, which both runs read: one at a time, every command of a batch is decided at
 * the instant the batch would have been.
 *
 * <p>Checked, and each failure an {@link InvariantViolation} naming the seed:
 *
 * <ul>
 *   <li><b>Batches answer as one at a time</b>: every command the same outcome, or the same failure,
 *       compared batch by batch as the batched run goes, so that the first batch to differ is the one
 *       named.
 *   <li><b>Batches leave the rows one at a time leaves</b>: every shard and its version, every
 *       reservation and its version, every outbox row in sequence, and every key's record.
 *   <li><b>A batch on its own never conflicts</b>: with no other writer, a refused batch write is a
 *       batch written at the wrong versions, which deciding it again would hide.
 *   <li>{@link Invariants} after every command of the first run and every batch of the second, and
 *       {@link History} after each.
 * </ul>
 */
public final class BatchEquivalence {

    /** Batches in a history, after the one that stocks it. */
    static final int BATCHES = 60;

    /** Most commands one batch holds. */
    static final int MAX_BATCH = 12;

    private BatchEquivalence() {}

    /**
     * What a history did, so that a test can assert it did the hard things rather than only that it
     * passed.
     *
     * @param seed the history's seed
     * @param commands how many commands it ran
     * @param batches how many batches
     * @param batchesOfMany of those, how many had more than one command
     * @param keysTwiceInABatch batches in which one key was sent more than once
     * @param earlierHoldsFinished holds committed or released by a batch after the one that took them
     * @param holdsFinishedInTheirOwnBatch holds committed or released in the batch that took them
     * @param shortfallsOnTheirOwn commands of the batched run decided on their own with the expired holds
     *     loaded, because they were short without them
     * @param deadlinesExceeded commands whose caller had already gone
     * @param keysReusedAndRefused commands refused for using a key another request had used
     * @param invariantChecks how many times every invariant was checked, across both runs
     */
    public record Report(
            long seed,
            int commands,
            int batches,
            int batchesOfMany,
            int keysTwiceInABatch,
            int earlierHoldsFinished,
            int holdsFinishedInTheirOwnBatch,
            long shortfallsOnTheirOwn,
            int deadlinesExceeded,
            int keysReusedAndRefused,
            long invariantChecks) {

        /**
         * One line for a log or a failure message.
         *
         * @return the counts, in a fixed order
         */
        public String summary() {
            return "seed=" + seed + " commands=" + commands + " batches=" + batches + " (of many=" + batchesOfMany + ")"
                    + " keysTwiceInABatch=" + keysTwiceInABatch + " earlierHoldsFinished=" + earlierHoldsFinished
                    + " holdsFinishedInTheirOwnBatch=" + holdsFinishedInTheirOwnBatch
                    + " shortfallsOnTheirOwn=" + shortfallsOnTheirOwn + " deadlinesExceeded=" + deadlinesExceeded
                    + " keysReusedAndRefused=" + keysReusedAndRefused + " checks=" + invariantChecks;
        }
    }

    /**
     * Runs one history both ways and compares them.
     *
     * @param seed the history
     * @param fresh an empty ledger, each time it is asked; asked twice, and the first one's rows are
     *     read before the second is asked for, so a supplier may empty one database and hand it out
     *     again
     * @param <L> a ledger that can also be inspected
     * @return what the history did
     * @throws InvariantViolation on the first difference, or the first invariant broken
     */
    public static <L extends Ledger & LedgerInspector> Report check(long seed, Supplier<L> fresh) {
        List<Batch> history = history(seed);
        Observed one = oneAtATime(seed, history, fresh.get());
        Observed many = inBatches(seed, history, fresh.get(), one);
        compareRows(seed, history.size(), one.rows(), many.rows());
        return report(seed, history, many, one.checks() + many.checks());
    }

    /** One batch of a history: the instant it runs at, and its commands in the order they arrived. */
    record Batch(Instant at, List<Till.Call> calls) {}

    /** What a run answered, what it left, and how often it checked. */
    private record Observed(List<Till.Answer> answers, Rows rows, long checks, long shortfallsOnTheirOwn) {}

    /** What a run left. */
    private record Rows(
            List<StockShard> shards,
            List<Reservation> reservations,
            List<OutboxEntry> events,
            Map<IdempotencyKey, Optional<OutcomeRecord>> records) {}

    private static <L extends Ledger & LedgerInspector> Observed oneAtATime(long seed, List<Batch> history, L ledger) {
        Moved clock = new Moved();
        Till till = Till.builder(ledger).clock(clock).build();
        Invariants invariants = new Invariants(seed);
        History told = new History();
        List<Till.Answer> answers = new ArrayList<>();
        long step = 0;
        for (Batch batch : history) {
            clock.now = batch.at();
            for (Till.Call call : batch.calls()) {
                Till.Answer answer = alone(till, call);
                answers.add(answer);
                step++;
                if (answeredUnderItsKey(answer)) {
                    told.record(step, 0, call.command(), answer.outcome());
                }
                invariants.check(step, batch.at(), ledger);
            }
        }
        told.check(seed, ledger);
        return new Observed(answers, rows(ledger, history), invariants.checkCount(), 0);
    }

    private static <L extends Ledger & LedgerInspector> Observed inBatches(
            long seed, List<Batch> history, L ledger, Observed reference) {
        Moved clock = new Moved();
        Counting counting = new Counting(ledger);
        Till till = Till.builder(counting).clock(clock).build();
        Invariants invariants = new Invariants(seed);
        History told = new History();
        List<Till.Answer> answers = new ArrayList<>();
        long step = 0;
        for (Batch batch : history) {
            clock.now = batch.at();
            Till.BatchResult result = till.executeAll(batch.calls());
            step++;
            if (result.conflicts() > 0) {
                throw new InvariantViolation("A batch on its own never conflicts", seed, step,
                        result.conflicts() + " of its writes were refused with no other writer: " + batch.calls());
            }
            for (int i = 0; i < batch.calls().size(); i++) {
                int index = answers.size();
                Command command = batch.calls().get(i).command();
                Till.Answer expected = reference.answers().get(index);
                Till.Answer actual = result.answers().get(i);
                if (!same(expected, actual)) {
                    throw new InvariantViolation("Batches answer as one at a time", seed, step,
                            "command " + index + ", " + (i + 1) + " of " + batch.calls().size() + " in its batch, "
                                    + command + ", was answered " + describe(expected) + " one at a time and "
                                    + describe(actual) + " in its batch");
                }
                answers.add(actual);
                if (answeredUnderItsKey(actual)) {
                    told.record(index + 1, 0, command, actual.outcome());
                }
            }
            invariants.check(step, batch.at(), ledger);
        }
        told.check(seed, ledger);
        return new Observed(answers, rows(ledger, history), invariants.checkCount(), counting.reclaiming);
    }

    /**
     * Whether an answer is one {@link History} holds a key to: every outcome but the refusal of a key
     * used for a different request, which is never recorded under that key (ADR 3) and is, by design,
     * not the answer the key's own request got.
     */
    private static boolean answeredUnderItsKey(Till.Answer answer) {
        return answer.outcome() != null
                && !(answer.outcome() instanceof Outcome.Rejected rejected
                        && rejected.code() == RejectionCode.IDEMPOTENCY_KEY_REUSED);
    }

    private static Till.Answer alone(Till till, Till.Call call) {
        try {
            return Till.Answer.of(till.execute(call.command(), call.deadline()));
        } catch (RuntimeException e) {
            return Till.Answer.failed(e);
        }
    }

    /** The same outcome, or a failure of the same kind saying the same thing. */
    private static boolean same(Till.Answer one, Till.Answer other) {
        if (one.outcome() != null || other.outcome() != null) {
            return one.outcome() != null && one.outcome().equals(other.outcome());
        }
        return one.failure().getClass() == other.failure().getClass()
                && String.valueOf(one.failure().getMessage()).equals(String.valueOf(other.failure().getMessage()));
    }

    private static String describe(Till.Answer answer) {
        return answer.outcome() != null ? answer.outcome().toString() : answer.failure().toString();
    }

    /** Every row a run left, and the record of every key the history used, read the same way for both. */
    private static <L extends Ledger & LedgerInspector> Rows rows(L ledger, List<Batch> history) {
        Instant end = history.get(history.size() - 1).at();
        Map<IdempotencyKey, Optional<OutcomeRecord>> records = new LinkedHashMap<>();
        for (Batch batch : history) {
            for (Till.Call call : batch.calls()) {
                Command command = call.command();
                command.idempotencyKey().filter(key -> !records.containsKey(key)).ifPresent(key ->
                        records.put(key, ledger.load(command, end, 0).recordedOutcome()));
            }
        }
        return new Rows(ledger.allShards(), ledger.allReservations(), ledger.allEvents(), records);
    }

    private static void compareRows(long seed, long step, Rows one, Rows many) {
        compare(seed, step, "shards", one.shards(), many.shards());
        compare(seed, step, "reservations", one.reservations(), many.reservations());
        compare(seed, step, "outbox rows", one.events(), many.events());
        compare(seed, step, "idempotency records", List.copyOf(one.records().entrySet()), List.copyOf(many.records().entrySet()));
    }

    private static <T> void compare(long seed, long step, String what, List<T> one, List<T> many) {
        if (one.equals(many)) {
            return;
        }
        int at = 0;
        while (at < Math.min(one.size(), many.size()) && one.get(at).equals(many.get(at))) {
            at++;
        }
        throw new InvariantViolation("Batches leave the rows one at a time leaves", seed, step,
                what + " differ first at " + at + ": one at a time left "
                        + (at < one.size() ? one.get(at) : "nothing more") + " and batches "
                        + (at < many.size() ? many.get(at) : "nothing more"));
    }

    private static Report report(long seed, List<Batch> history, Observed many, long checks) {
        int commands = 0;
        int batchesOfMany = 0;
        int keysTwiceInABatch = 0;
        int earlierHoldsFinished = 0;
        int ownBatchHoldsFinished = 0;
        int deadlinesExceeded = 0;
        int keysReused = 0;
        Map<ReservationId, Integer> takenIn = new HashMap<>();
        Set<ReservationId> finished = new HashSet<>();
        for (int b = 0; b < history.size(); b++) {
            List<Till.Call> calls = history.get(b).calls();
            if (calls.size() > 1) {
                batchesOfMany++;
            }
            Set<IdempotencyKey> keys = new HashSet<>();
            boolean twice = false;
            for (Till.Call call : calls) {
                Till.Answer answer = many.answers().get(commands++);
                twice |= call.command().idempotencyKey().map(key -> !keys.add(key)).orElse(false);
                if (answer.failure() instanceof DeadlineExceededException) {
                    deadlinesExceeded++;
                }
                switch (answer.outcome()) {
                    case Outcome.Reserved reserved -> takenIn.putIfAbsent(reserved.id(), b);
                    case Outcome.Committed committed when finished.add(committed.id()) -> {
                        if (finishedIn(call.command(), b, takenIn)) {
                            ownBatchHoldsFinished++;
                        } else {
                            earlierHoldsFinished++;
                        }
                    }
                    case Outcome.Released released when finished.add(released.id()) -> {
                        if (finishedIn(call.command(), b, takenIn)) {
                            ownBatchHoldsFinished++;
                        } else {
                            earlierHoldsFinished++;
                        }
                    }
                    case Outcome.Rejected rejected when rejected.code() == RejectionCode.IDEMPOTENCY_KEY_REUSED -> keysReused++;
                    case null, default -> { /* nothing to count */ }
                }
            }
            if (twice) {
                keysTwiceInABatch++;
            }
        }
        return new Report(
                seed, commands, history.size(), batchesOfMany, keysTwiceInABatch, earlierHoldsFinished,
                ownBatchHoldsFinished, many.shortfallsOnTheirOwn(), deadlinesExceeded, keysReused, checks);
    }

    /** Whether a commit or release finished a hold taken in this same batch, rather than an earlier one. */
    private static boolean finishedIn(Command command, int batch, Map<ReservationId, Integer> takenIn) {
        return command.targetReservation().map(takenIn::get).map(taken -> taken == batch).orElse(false);
    }

    /**
     * The history for a seed: a first batch that stocks five SKUs and splits two of them, then
     * {@link #BATCHES} batches of one to {@link #MAX_BATCH} commands, five seconds apart, and one time
     * in five a minute and a quarter apart, so that holds of half a minute to a minute expire under
     * the commands that follow.
     */
    static List<Batch> history(long seed) {
        Generator generator = new Generator(new Random(seed));
        List<Batch> history = new ArrayList<>();
        Instant at = SimConfig.EPOCH;
        history.add(new Batch(at, generator.stockEverything()));
        for (int b = 1; b <= BATCHES; b++) {
            at = at.plus(Duration.ofSeconds(generator.random.nextDouble() < 0.2 ? 75 : 5));
            history.add(new Batch(at, generator.batch(b, at)));
        }
        return history;
    }

    /** Chooses commands from what the history has done so far, never from what anything answered. */
    private static final class Generator {

        private final Random random;
        private final List<Sku> skus = new ArrayList<>();
        private final List<Command> past = new ArrayList<>();
        private final List<Command.Reserve> reserves = new ArrayList<>();
        private final List<ReservationId> ids = new ArrayList<>();
        private long sequence;

        private Generator(Random random) {
            this.random = random;
            for (int i = 0; i < 5; i++) {
                skus.add(Sku.of("sku-" + i));
            }
        }

        private List<Till.Call> stockEverything() {
            List<Till.Call> calls = new ArrayList<>();
            for (Sku sku : skus) {
                calls.add(Till.Call.of(remember(new Command.Adjust(key(), sku, 12 + random.nextInt(10)))));
            }
            calls.add(Till.Call.of(remember(new Command.Shard(key(), skus.get(1), 3))));
            calls.add(Till.Call.of(remember(new Command.Shard(key(), skus.get(2), 4))));
            return calls;
        }

        private List<Till.Call> batch(int b, Instant at) {
            int size = 1 + random.nextInt(MAX_BATCH);
            List<Command> mine = new ArrayList<>();
            List<Till.Call> calls = new ArrayList<>();
            for (int i = 0; i < size; i++) {
                Command command = remember(choose(mine));
                mine.add(command);
                double roll = random.nextDouble();
                Instant deadline = roll < 0.06 ? at.minusMillis(1) : roll < 0.16 ? at.plusSeconds(60) : null;
                calls.add(new Till.Call(command, deadline));
            }
            return calls;
        }

        private Command choose(List<Command> mine) {
            double roll = random.nextDouble();
            if (roll < 0.28 || ids.isEmpty()) {
                return reserve(newId(), lines(1 + random.nextInt(2), 4));
            }
            if (roll < 0.40) {
                return new Command.Commit(key(), heldBy(mine));
            }
            if (roll < 0.49) {
                return new Command.Release(key(), heldBy(mine));
            }
            if (roll < 0.58) {
                return again(mine);
            }
            if (roll < 0.62) {
                // The key of an earlier hold, for a different basket: refused, never served.
                Command.Reserve earlier = reserves.get(random.nextInt(reserves.size()));
                List<Line> other = new ArrayList<>();
                earlier.lines().forEach(line -> other.add(new Line(line.sku(), line.quantity() + 1)));
                return new Command.Reserve(earlier.key(), newId(), other, earlier.ttl());
            }
            if (roll < 0.65) {
                return reserve(ids.get(random.nextInt(ids.size())), lines(1, 2));
            }
            if (roll < 0.74) {
                long delta = random.nextDouble() < 0.8 ? 1 + random.nextInt(8) : -(1 + random.nextInt(5));
                return new Command.Adjust(key(), sku(), delta);
            }
            if (roll < 0.77) {
                return new Command.Shard(key(), sku(), 2 + random.nextInt(5));
            }
            if (roll < 0.80) {
                return new Command.Sweep(1 + random.nextInt(4));
            }
            if (roll < 0.94) {
                // More than a SKU is likely to have left: short, often with expired holds on it.
                return reserve(newId(), List.of(new Line(sku(), 6 + random.nextInt(10))));
            }
            return random.nextBoolean()
                    ? reserve(newId(), List.of(new Line(Sku.of("ghost"), 1)))
                    : new Command.Commit(key(), ReservationId.of("never-" + (++sequence)));
        }

        /** A hold to finish: one taken earlier in this batch about half the time there is one, otherwise any. */
        private ReservationId heldBy(List<Command> mine) {
            List<ReservationId> here = new ArrayList<>();
            for (Command command : mine) {
                if (command instanceof Command.Reserve reserve) {
                    here.add(reserve.reservationId());
                }
            }
            if (!here.isEmpty() && random.nextBoolean()) {
                return here.get(random.nextInt(here.size()));
            }
            return ids.get(random.nextInt(ids.size()));
        }

        /** A command sent again, as a client retries: from this batch about half the time there is one. */
        private Command again(List<Command> mine) {
            List<Command> from = !mine.isEmpty() && random.nextBoolean() ? mine : past;
            Command command = from.get(random.nextInt(from.size()));
            if (command instanceof Command.Reserve reserve) {
                // A stateless server mints a new id for every copy of a request.
                return new Command.Reserve(reserve.key(), newId(), reserve.lines(), reserve.ttl());
            }
            return command;
        }

        private Command.Reserve reserve(ReservationId id, List<Line> lines) {
            Command.Reserve reserve = new Command.Reserve(key(), id, lines, Duration.ofSeconds(30 + random.nextInt(31)));
            reserves.add(reserve);
            return reserve;
        }

        private List<Line> lines(int count, int most) {
            List<Sku> candidates = new ArrayList<>(skus);
            List<Line> lines = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                lines.add(new Line(candidates.remove(random.nextInt(candidates.size())), 1 + random.nextInt(most)));
            }
            return lines;
        }

        private Command remember(Command command) {
            past.add(command);
            if (command instanceof Command.Reserve reserve && !ids.contains(reserve.reservationId())) {
                ids.add(reserve.reservationId());
            }
            return command;
        }

        private Sku sku() {
            return skus.get(random.nextInt(skus.size()));
        }

        private IdempotencyKey key() {
            return IdempotencyKey.of("k" + (++sequence));
        }

        private ReservationId newId() {
            return ReservationId.of("r" + (++sequence));
        }
    }

    /** The instant of the batch being run, which every read of the clock during it returns. */
    private static final class Moved extends Clock {
        private Instant now = SimConfig.EPOCH;

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

    /** Counts the loads that bring expired holds for a command short without them. */
    private static final class Counting implements Ledger {
        private final Ledger inner;
        private long reclaiming;

        private Counting(Ledger inner) {
            this.inner = inner;
        }

        @Override
        public Snapshot load(Command command, Instant now, int reclaimLimit) {
            if (reclaimLimit > 0 && !(command instanceof Command.Sweep)) {
                reclaiming++;
            }
            return inner.load(command, now, reclaimLimit);
        }

        @Override
        public boolean apply(Decision decision) {
            return inner.apply(decision);
        }

        @Override
        public BatchSnapshot loadBatch(List<Command> commands) {
            return inner.loadBatch(commands);
        }

        @Override
        public boolean applyBatch(BatchDecision decision) {
            return inner.applyBatch(decision);
        }
    }
}
