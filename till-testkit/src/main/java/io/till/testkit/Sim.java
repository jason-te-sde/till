package io.till.testkit;

import io.till.core.Command;
import io.till.core.Decision;
import io.till.core.Event;
import io.till.core.IdempotencyKey;
import io.till.core.Kernel;
import io.till.core.Ledger;
import io.till.core.LedgerInspector;
import io.till.core.Line;
import io.till.core.Outcome;
import io.till.core.RejectionCode;
import io.till.core.ReservationId;
import io.till.core.Sku;
import io.till.core.Snapshot;
import io.till.core.mem.InMemoryLedger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Many callers contending for the same stock, interleaved by a seed instead of by a scheduler.
 *
 * <p>A command is not one operation here. It is three phases — load a snapshot, decide against it,
 * try to apply — and the simulator advances <b>one phase of one caller</b> per step. That is what
 * makes the races real: between one caller loading and the same caller applying, any number of other
 * callers can have decided and applied against the rows it is holding a stale view of. Threads would
 * produce the same races and would produce different ones every run.
 *
 * <p>On top of the interleaving it injects the four things that actually go wrong in production:
 *
 * <ul>
 *   <li><b>A process dies between deciding and committing.</b> The decision is thrown away and the
 *       caller starts over, which must not leave a half-applied change behind.
 *   <li><b>An answer never arrives.</b> The command was applied and the caller does not know, so it
 *       retries something that has already happened. This is the case idempotency exists for, and
 *       the one every hand-rolled implementation gets wrong.
 *   <li><b>The clock jumps.</b> Holds expire in bunches, the way they do when a process stalls.
 *   <li><b>A caller walks away.</b> Its hold is left to expire, which is what a customer closing a
 *       tab looks like.
 *   </ul>
 *
 * <p>{@link Invariants} runs after every step, and {@link History} is checked once the run has
 * drained. {@link SimReport} says what the run did, so a test can assert it was hostile rather than
 * merely green.
 *
 * <p>Runs against any {@link Ledger} that can also be inspected, which is how the PostgreSQL adapter
 * is checked against the same schedules as the in-memory one.
 */
public final class Sim {

    /**
     * Attempts before the simulator decides a command can never be applied.
     *
     * <p>Far above anything contention alone produces. A command that reaches this is not unlucky:
     * something is refusing it deterministically, and the run fails rather than spinning.
     */
    private static final int MAX_ATTEMPTS = 200;

    private final SimConfig config;
    private final Random random;
    private final Ledger ledger;
    private final LedgerInspector inspector;
    private final Invariants invariants;
    private final History history;
    private final List<Sku> skus = new ArrayList<>();
    private final List<Caller> callers = new ArrayList<>();
    private final Counters counters = new Counters();

    private Instant now;
    private long step;
    private long sequence;

    /**
     * A simulator over a fresh in-memory ledger.
     *
     * @param config the run to perform
     */
    public Sim(SimConfig config) {
        this(config, new InMemoryLedger());
    }

    /**
     * A simulator over a ledger of your own.
     *
     * @param config the run to perform
     * @param target the ledger under test, which must start empty
     * @param <L> a ledger that can also be inspected
     */
    public <L extends Ledger & LedgerInspector> Sim(SimConfig config, L target) {
        this.config = config;
        this.random = new Random(config.seed());
        if (config.flaw() == Flaw.NONE) {
            this.ledger = target;
            this.inspector = target;
        } else {
            FlawedLedger flawed = new FlawedLedger(target, config.flaw());
            this.ledger = flawed;
            this.inspector = flawed;
        }
        this.invariants = new Invariants(config.seed());
        this.history = new History();
        this.now = config.start();
        for (int i = 0; i < config.skus(); i++) {
            skus.add(Sku.of("sku-" + i));
        }
        for (int i = 0; i < config.clients(); i++) {
            callers.add(new Caller(i));
        }
    }

    /**
     * Runs a whole simulation and checks everything.
     *
     * @param config the run to perform
     * @return what it did
     * @throws InvariantViolation if any property broke, naming the seed and the step
     */
    public static SimReport run(SimConfig config) {
        return new Sim(config).run();
    }

    /**
     * Runs this simulation.
     *
     * @return what it did
     * @throws InvariantViolation if any property broke, naming the seed and the step
     */
    public SimReport run() {
        seedStock();
        for (step = 1; step <= config.steps(); step++) {
            advanceClock();
            if (random.nextDouble() < config.sweepChance()) {
                sweep();
            } else {
                advanceOneCaller(false);
            }
            invariants.check(step, now, inspector);
        }
        long drained = drain();
        history.check(config.seed(), inspector);
        return report(drained);
    }

    /**
     * The ledger's read-only view, for a test that wants to look at the final state.
     *
     * @return the inspector
     */
    public LedgerInspector inspector() {
        return inspector;
    }

    /**
     * What the callers were told.
     *
     * @return the history
     */
    public History history() {
        return history;
    }

    private void seedStock() {
        for (Sku sku : skus) {
            Command command = new Command.Adjust(IdempotencyKey.of("seed-" + sku), sku, config.startingStock());
            Snapshot snapshot = ledger.load(command, now, 0);
            if (!ledger.apply(Kernel.decide(snapshot, command, now))) {
                throw new IllegalStateException("seeding " + sku + " failed on an empty ledger");
            }
            counters.adjusted++;
        }
    }

    private void advanceClock() {
        now = now.plus(config.stepTime());
        if (random.nextDouble() < config.timeJumpChance()) {
            now = now.plus(config.timeJump());
        }
    }

    /**
     * Decides a command, turning any failure into a finding that carries its own reproduction.
     *
     * <p>The kernel refusing to build an impossible value is a real result, but on its own it says
     * only that some arithmetic went negative three frames down. What is worth having is the
     * snapshot it went negative against: that is the entire input to a pure function, so it
     * reproduces the failure without the rest of the run.
     */
    private Decision decide(Snapshot snapshot, Command command, Instant at) {
        try {
            return Kernel.decide(snapshot, command, at);
        } catch (RuntimeException e) {
            throw new InvariantViolation(
                    "The kernel can decide any command against any snapshot the ledger produced",
                    config.seed(),
                    step,
                    "deciding " + command + " at " + at + " against " + snapshot + " raised " + e);
        }
    }

    /** The background sweeper, run as one atomic step because nothing races with it usefully. */
    private void sweep() {
        Command command = new Command.Sweep(16);
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            Decision decision = decide(ledger.load(command, now, 16), command, now);
            if (!decision.writes() || ledger.apply(decision)) {
                counters.sweeps++;
                return;
            }
            counters.conflicts++;
        }
        throw new IllegalStateException("a sweep could not be applied in " + MAX_ATTEMPTS + " attempts");
    }

    private void advanceOneCaller(boolean draining) {
        Caller caller = callers.get(random.nextInt(callers.size()));
        switch (caller.phase) {
            case IDLE -> begin(caller, draining);
            case LOADED -> decide(caller, draining);
            case DECIDED -> apply(caller, draining);
        }
    }

    private void begin(Caller caller, boolean draining) {
        if (caller.command == null) {
            if (draining) {
                return;
            }
            caller.command = choose(caller);
            if (caller.command == null) {
                return;
            }
        } else if (caller.clientRetry && caller.command instanceof Command.Reserve reserve) {
            // A caller that never heard back re-sends the request, and a stateless server mints a
            // fresh reservation id for it. The key is the same, so the kernel has to recognise the
            // request without help from the id — which is why the id is not in the fingerprint.
            caller.command =
                    new Command.Reserve(reserve.key(), nextReservationId(caller), reserve.lines(), reserve.ttl());
        }
        caller.clientRetry = false;
        caller.observedAt = now;
        caller.snapshot = ledger.load(caller.command, now, 16);
        caller.phase = Phase.LOADED;
    }

    private void decide(Caller caller, boolean draining) {
        if (!draining && random.nextDouble() < config.crashBeforeApplyChance()) {
            // The process died between deciding and committing. Nothing was written, and whatever
            // reloads next must not find a half-applied change.
            counters.crashesInjected++;
            caller.clientRetry = true;
            caller.phase = Phase.IDLE;
            caller.snapshot = null;
            return;
        }
        caller.decision = decide(caller.snapshot, caller.command, caller.observedAt);
        caller.phase = Phase.DECIDED;
    }

    private void apply(Caller caller, boolean draining) {
        Decision decision = caller.decision;
        if (!decision.writes()) {
            if (caller.command.idempotencyKey().isPresent() && caller.snapshot.recordedOutcome().isPresent()) {
                counters.replays++;
            }
            complete(caller, decision.outcome());
            return;
        }
        if (!ledger.apply(decision)) {
            counters.conflicts++;
            if (++caller.attempts > MAX_ATTEMPTS) {
                throw new InvariantViolation(
                        "A command can always eventually be applied",
                        config.seed(),
                        step,
                        caller.command + " was refused " + caller.attempts + " times running");
            }
            caller.phase = Phase.IDLE;
            return;
        }
        if (!draining && random.nextDouble() < config.lostAckChance()) {
            // Applied, and the caller will never know. It retries with the same key, which must
            // return what already happened rather than doing it a second time.
            counters.lostAcks++;
            caller.clientRetry = true;
            caller.attempts = 0;
            caller.phase = Phase.IDLE;
            return;
        }
        complete(caller, decision.outcome());
    }

    private void complete(Caller caller, Outcome outcome) {
        history.record(step, caller.id, caller.command, outcome);
        counters.observe(outcome);
        switch (outcome) {
            case Outcome.Reserved reserved -> caller.holding = reserved.id();
            case Outcome.Committed ignored -> caller.holding = null;
            case Outcome.Released ignored -> caller.holding = null;
            case Outcome.Rejected ignored -> {
                // A refused commit or release is the end of that hold as far as this caller is
                // concerned: it expired, or somebody else finished it.
                if (caller.command instanceof Command.Commit || caller.command instanceof Command.Release) {
                    caller.holding = null;
                }
            }
            default -> { /* adjustments and sweeps change nothing about what the caller holds */ }
        }
        caller.past.add(caller.command);
        caller.command = null;
        caller.snapshot = null;
        caller.decision = null;
        caller.attempts = 0;
        caller.phase = Phase.IDLE;
    }

    private Command choose(Caller caller) {
        if (caller.holding != null) {
            double roll = random.nextDouble();
            if (roll < config.abandonChance()) {
                // Walked away. The hold is now somebody else's problem, which is the point.
                caller.holding = null;
                return null;
            }
            ReservationId id = caller.holding;
            return roll < config.abandonChance() + (1 - config.abandonChance()) / 2
                    ? new Command.Commit(nextKey(caller), id)
                    : new Command.Release(nextKey(caller), id);
        }
        double roll = random.nextDouble();
        if (roll < config.duplicateChance() && !caller.past.isEmpty()) {
            counters.duplicates++;
            return caller.past.get(random.nextInt(caller.past.size()));
        }
        if (roll < config.duplicateChance() + config.adjustChance()) {
            long delta = 1 + random.nextInt((int) config.maxQuantity());
            return new Command.Adjust(nextKey(caller), skus.get(random.nextInt(skus.size())), delta);
        }
        return new Command.Reserve(nextKey(caller), nextReservationId(caller), randomLines(), config.ttl());
    }

    private List<Line> randomLines() {
        List<Sku> candidates = new ArrayList<>(skus);
        int count = 1 + random.nextInt(config.maxLines());
        List<Line> lines = new ArrayList<>(count);
        for (int i = 0; i < count && !candidates.isEmpty(); i++) {
            Sku sku = candidates.remove(random.nextInt(candidates.size()));
            lines.add(new Line(sku, 1 + random.nextInt((int) config.maxQuantity())));
        }
        return lines;
    }

    private IdempotencyKey nextKey(Caller caller) {
        return IdempotencyKey.of("c" + caller.id + "-op" + (++sequence));
    }

    private ReservationId nextReservationId(Caller caller) {
        return ReservationId.of("r" + caller.id + "-" + (++sequence));
    }

    /**
     * Lets every caller finish what it started, with no new commands and no injected faults.
     *
     * <p>Without this the run would end with commands in flight, and the final-state checks would
     * have to tolerate a ledger that is mid-operation — which means tolerating exactly the states a
     * real bug produces.
     */
    private long drain() {
        long drainSteps = 0;
        long budget = (long) callers.size() * MAX_ATTEMPTS * 4L;
        while (drainSteps < budget) {
            Caller busy = callers.stream().filter(caller -> caller.phase != Phase.IDLE || caller.command != null)
                    .findFirst()
                    .orElse(null);
            if (busy == null) {
                return drainSteps;
            }
            drainSteps++;
            switch (busy.phase) {
                case IDLE -> begin(busy, true);
                case LOADED -> decide(busy, true);
                case DECIDED -> apply(busy, true);
            }
            invariants.check(config.steps() + drainSteps, now, inspector);
        }
        throw new IllegalStateException("callers would not drain in " + budget + " steps");
    }

    private SimReport report(long drainSteps) {
        return new SimReport(
                config.seed(),
                config.steps(),
                drainSteps,
                invariants.checkCount(),
                history.size(),
                counters.conflicts,
                counters.replays,
                counters.crashesInjected,
                counters.lostAcks,
                counters.duplicates,
                counters.reserved,
                counters.committed,
                counters.released,
                counters.expired(inspector),
                counters.adjusted,
                counters.sweeps,
                counters.rejections,
                counters.outOfStock,
                now);
    }

    private enum Phase {
        IDLE,
        LOADED,
        DECIDED
    }

    /** One logical caller, part-way through one command. */
    private static final class Caller {
        private final int id;
        private final List<Command> past = new ArrayList<>();
        private Phase phase = Phase.IDLE;
        private Command command;
        private Snapshot snapshot;
        private Decision decision;
        private Instant observedAt;
        private ReservationId holding;
        private boolean clientRetry;
        private int attempts;

        private Caller(int id) {
            this.id = id;
        }
    }

    /** Everything a report quotes. */
    private static final class Counters {
        private long conflicts;
        private long replays;
        private long crashesInjected;
        private long lostAcks;
        private long duplicates;
        private long reserved;
        private long committed;
        private long released;
        private long adjusted;
        private long sweeps;
        private long rejections;
        private long outOfStock;

        private void observe(Outcome outcome) {
            switch (outcome) {
                case Outcome.Reserved ignored -> reserved++;
                case Outcome.Committed ignored -> committed++;
                case Outcome.Released ignored -> released++;
                case Outcome.Adjusted ignored -> adjusted++;
                case Outcome.Swept ignored -> { /* counted where it is run */ }
                case Outcome.Rejected rejected -> {
                    rejections++;
                    if (rejected.code() == RejectionCode.INSUFFICIENT_STOCK) {
                        outOfStock++;
                    }
                }
            }
        }

        /** Expiries are not answers to anybody, so they are counted from the outbox. */
        private long expired(LedgerInspector inspector) {
            return inspector.allEvents().stream()
                    .filter(entry -> entry.event() instanceof Event.StockExpired)
                    .count();
        }
    }
}
