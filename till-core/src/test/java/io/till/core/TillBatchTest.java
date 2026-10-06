package io.till.core;

import static io.till.core.Fixtures.T0;
import static io.till.core.Fixtures.TTL;
import static io.till.core.Fixtures.key;
import static io.till.core.Fixtures.line;
import static io.till.core.Fixtures.rid;
import static io.till.core.Fixtures.sku;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.till.core.mem.InMemoryLedger;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link Till#executeAll}: commands that wait together, decided together against one snapshot and
 * written together, with the answers they would have had one at a time (ADR 16).
 *
 * <p>Each case here is one rule of the batch, with its answers worked out by hand. The suite that
 * holds random histories to the answers of one at a time, row for row, is the equivalence suite in
 * {@code till-testkit}, and {@code JdbcBatchEquivalenceTest} runs it against PostgreSQL.
 */
class TillBatchTest {

    private final SteppingClock clock = new SteppingClock(T0);
    private final InMemoryLedger ledger = new InMemoryLedger();
    private final Recording recording = new Recording(ledger);

    @Test
    @DisplayName("a batch is one load and one write, however many commands are in it")
    void aBatchIsOneLoadAndOneWrite() {
        stock("widget", 10);
        Till till = Till.builder(recording).clock(clock).build();
        recording.calls.clear();

        Till.BatchResult result = till.executeAll(List.of(
                call(new Command.Reserve(key("k1"), rid("r1"), List.of(line("widget", 2)), TTL)),
                call(new Command.Reserve(key("k2"), rid("r2"), List.of(line("widget", 3)), TTL)),
                call(new Command.Adjust(key("k3"), sku("widget"), 5)),
                call(new Command.Release(key("k4"), rid("r1")))));

        assertEquals(List.of("loadBatch k1 k2 k3 k4", "applyBatch"), recording.calls);
        assertEquals(
                List.of(Outcome.Reserved.class, Outcome.Reserved.class, Outcome.Adjusted.class, Outcome.Released.class),
                result.answers().stream().map(answer -> answer.outcome().getClass()).toList());
        assertEquals(0, result.conflicts());
        StockItem widget = ledger.stock(sku("widget")).orElseThrow();
        assertEquals(15, widget.onHand());
        assertEquals(3, widget.reserved(), "r1's two given back, r2's three still held");
    }

    @Test
    @DisplayName("each command is decided against what the commands before it in the batch did")
    void eachCommandSeesTheOnesBeforeIt() {
        Till till = Till.builder(ledger).clock(clock).build();

        List<Till.Answer> answers = till.executeAll(List.of(
                call(new Command.Adjust(key("k1"), sku("widget"), 10)),
                call(new Command.Reserve(key("k2"), rid("r1"), List.of(line("widget", 3)), TTL)),
                call(new Command.Commit(key("k3"), rid("r1"))))).answers();

        assertEquals(new Outcome.Adjusted(sku("widget"), 10, 0), answers.get(0).outcome());
        assertEquals(new Outcome.Reserved(rid("r1"), List.of(line("widget", 3)), T0.plus(TTL)), answers.get(1).outcome());
        assertEquals(new Outcome.Committed(rid("r1"), T0), answers.get(2).outcome());
        assertEquals(new StockItem(sku("widget"), 7, 0, 2), ledger.stock(sku("widget")).orElseThrow(),
                "created, held from and sold from: two writes after the first, as one at a time");
        Reservation r1 = ledger.reservation(rid("r1")).orElseThrow();
        assertEquals(ReservationState.COMMITTED, r1.state());
        assertEquals(1, r1.version());
    }

    @Test
    @DisplayName("a key sent twice in one batch is executed once, and both are told the same")
    void aKeyTwiceInOneBatchIsExecutedOnce() {
        stock("widget", 10);
        Till till = Till.builder(ledger).clock(clock).build();

        // A retry of a request whose first copy is still queued: the same key, and, from a stateless
        // server, a reservation id of its own.
        List<Till.Answer> answers = till.executeAll(List.of(
                call(new Command.Reserve(key("k1"), rid("r1"), List.of(line("widget", 2)), TTL)),
                call(new Command.Reserve(key("k1"), rid("r2"), List.of(line("widget", 2)), TTL)))).answers();

        Outcome first = new Outcome.Reserved(rid("r1"), List.of(line("widget", 2)), T0.plus(TTL));
        assertEquals(first, answers.get(0).outcome());
        assertEquals(first, answers.get(1).outcome(), "the second is the first's answer, its own id and all");
        assertEquals(2, ledger.stock(sku("widget")).orElseThrow().reserved(), "held once");
        assertTrue(ledger.reservation(rid("r2")).isEmpty());
    }

    @Test
    @DisplayName("a key used again in one batch for a different request is refused, as it would be one at a time")
    void aKeyReusedInOneBatchIsRefused() {
        stock("widget", 10);
        Till till = Till.builder(ledger).clock(clock).build();

        List<Till.Answer> answers = till.executeAll(List.of(
                call(new Command.Reserve(key("k1"), rid("r1"), List.of(line("widget", 2)), TTL)),
                call(new Command.Reserve(key("k1"), rid("r2"), List.of(line("widget", 3)), TTL)))).answers();

        assertInstanceOf(Outcome.Reserved.class, answers.get(0).outcome());
        assertEquals(RejectionCode.IDEMPOTENCY_KEY_REUSED, ((Outcome.Rejected) answers.get(1).outcome()).code());
    }

    @Test
    @DisplayName("a command short of stock is decided on its own, in its place, with the expired holds, and answers stay those of arrival order")
    void aShortfallIsDecidedOnItsOwnInItsPlace() {
        // Seven on hand, four of them held by a hold that has run out and has not been written off:
        // three available until something loads the expired hold.
        stock("widget", 7);
        Till.builder(ledger).clock(clock).build()
                .reserve(key("old"), rid("old"), List.of(line("widget", 4)), Duration.ofMinutes(1));
        clock.advance(Duration.ofMinutes(2));
        Till till = Till.builder(recording).clock(clock).build();
        recording.calls.clear();

        List<Till.Answer> answers = till.executeAll(List.of(
                call(new Command.Reserve(key("b"), rid("b"), List.of(line("widget", 5)), TTL)),
                call(new Command.Reserve(key("c"), rid("c"), List.of(line("widget", 3)), TTL)))).answers();

        // One at a time: b is short of three, writes off the expired four, and takes five of seven;
        // then c finds two. Decided after c instead, b would have been the one refused (ADR 16).
        assertInstanceOf(Outcome.Reserved.class, answers.get(0).outcome());
        Outcome.Rejected refused = (Outcome.Rejected) answers.get(1).outcome();
        assertEquals(List.of(new Outcome.Shortfall(sku("widget"), 3, 2)), refused.shortfalls());
        assertEquals(
                List.of(
                        "loadBatch b c", "load b 0", "load b " + Till.DEFAULT_RECLAIM_LIMIT, "apply",
                        "loadBatch c", "load c 0", "load c " + Till.DEFAULT_RECLAIM_LIMIT, "apply"),
                recording.calls,
                "b ends the run before it and goes through execute; c starts a run of its own and is short too");
    }

    @Test
    @DisplayName("with reclaiming turned off a shortfall is final, and is decided in the batch like any other answer")
    void aShortfallWithReclaimingOffStaysInTheBatch() {
        stock("widget", 10);
        Till till = Till.builder(recording).clock(clock).reclaimLimit(0).build();
        recording.calls.clear();

        List<Till.Answer> answers = till.executeAll(List.of(
                call(new Command.Reserve(key("k1"), rid("r1"), List.of(line("widget", 50)), TTL)),
                call(new Command.Reserve(key("k2"), rid("r2"), List.of(line("widget", 1)), TTL)))).answers();

        assertEquals(RejectionCode.INSUFFICIENT_STOCK, ((Outcome.Rejected) answers.get(0).outcome()).code());
        assertInstanceOf(Outcome.Reserved.class, answers.get(1).outcome());
        assertEquals(List.of("loadBatch k1 k2", "applyBatch"), recording.calls);
    }

    @Test
    @DisplayName("a sweep is run on its own, in its place")
    void aSweepRunsOnItsOwn() {
        stock("widget", 10);
        Till.builder(ledger).clock(clock).build()
                .reserve(key("old"), rid("old"), List.of(line("widget", 4)), Duration.ofMinutes(1));
        clock.advance(Duration.ofMinutes(2));
        Till till = Till.builder(recording).clock(clock).build();
        recording.calls.clear();

        List<Till.Answer> answers = till.executeAll(List.of(
                call(new Command.Reserve(key("k1"), rid("r1"), List.of(line("widget", 1)), TTL)),
                call(new Command.Sweep(10)),
                call(new Command.Reserve(key("k2"), rid("r2"), List.of(line("widget", 1)), TTL)))).answers();

        assertEquals(new Outcome.Swept(1), answers.get(1).outcome());
        assertEquals(
                List.of("loadBatch k1", "applyBatch", "load sweep 10", "apply", "loadBatch k2", "applyBatch"),
                recording.calls);
    }

    @Test
    @DisplayName("a command whose caller has already gone is answered DeadlineExceeded and never loaded")
    void aCommandPastItsDeadlineIsLeftOut() {
        stock("widget", 10);
        Till till = Till.builder(recording).clock(clock).build();
        recording.calls.clear();
        Command late = new Command.Reserve(key("late"), rid("late"), List.of(line("widget", 1)), TTL);

        List<Till.Answer> answers = till.executeAll(List.of(
                call(new Command.Reserve(key("k1"), rid("r1"), List.of(line("widget", 1)), TTL)),
                new Till.Call(late, T0.minusSeconds(1)),
                new Till.Call(new Command.Reserve(key("k3"), rid("r3"), List.of(line("widget", 1)), TTL), T0.plusSeconds(60))))
                .answers();

        DeadlineExceededException exceeded = assertInstanceOf(DeadlineExceededException.class, answers.get(1).failure());
        assertSame(late, exceeded.command());
        assertEquals(T0.minusSeconds(1), exceeded.deadline());
        assertInstanceOf(Outcome.Reserved.class, answers.get(0).outcome());
        assertInstanceOf(Outcome.Reserved.class, answers.get(2).outcome());
        assertEquals(List.of("loadBatch k1 k3", "applyBatch"), recording.calls);
        assertTrue(ledger.reservation(rid("late")).isEmpty());
    }

    @Test
    @DisplayName("a command whose deadline passes while its batch is loaded is not written, and the commands after it are decided without it")
    void aDeadlinePassingBeforeTheWriteTakesTheCommandOut() {
        stock("widget", 10);
        // The load takes a second; the first command's caller gives up half way through it.
        Advancing advancing = new Advancing(ledger, clock, Duration.ofSeconds(1));
        Till till = Till.builder(advancing).clock(clock).build();

        List<Till.Answer> answers = till.executeAll(List.of(
                new Till.Call(new Command.Reserve(key("k1"), rid("r1"), List.of(line("widget", 10)), TTL), T0.plusMillis(500)),
                call(new Command.Reserve(key("k2"), rid("r2"), List.of(line("widget", 5)), TTL)))).answers();

        assertInstanceOf(DeadlineExceededException.class, answers.get(0).failure());
        // With r1's ten written, r2 would have been refused; one at a time, r1 would never have been
        // written, and r2 holds five of ten.
        assertEquals(new Outcome.Reserved(rid("r2"), List.of(line("widget", 5)), T0.plus(TTL)), answers.get(1).outcome());
        assertTrue(ledger.reservation(rid("r1")).isEmpty());
        assertEquals(5, ledger.stock(sku("widget")).orElseThrow().reserved());
        assertEquals(1, advancing.batchLoads, "decided again from the same snapshot, not loaded again");
    }

    @Test
    @DisplayName("the commands after one whose caller has gone are decided again without it, from the same snapshot")
    void theCommandsAfterAGoneOneAreDecidedWithoutIt() {
        stock("widget", 10);
        Advancing advancing = new Advancing(ledger, clock, Duration.ofSeconds(1));
        Till till = Till.builder(advancing).clock(clock).build();

        Till.BatchResult result = till.executeAll(List.of(
                new Till.Call(new Command.Adjust(key("k1"), sku("widget"), -4), T0.plusMillis(500)),
                call(new Command.Adjust(key("k2"), sku("widget"), 1))));

        assertInstanceOf(DeadlineExceededException.class, result.answers().get(0).failure());
        // Decided after k1, k2 would report seven on hand, and its write would expect the version k1
        // left: refused, and loaded again. Decided again without k1 it reports eleven, and goes through.
        assertEquals(new Outcome.Adjusted(sku("widget"), 11, 0), result.answers().get(1).outcome());
        assertEquals(11, ledger.stock(sku("widget")).orElseThrow().onHand());
        assertEquals(1, advancing.batchLoads);
        assertEquals(0, result.conflicts());
    }

    @Test
    @DisplayName("a batch whose write finds a row moved is loaded and decided again, from what is there now")
    void aRefusedBatchIsDecidedAgainFromFreshState() {
        stock("widget", 10);
        // Between the batch's load and its write, somebody else takes six.
        Interfering interfering = new Interfering(ledger, inner ->
                Till.builder(inner).clock(clock).build()
                        .reserve(key("elsewhere"), rid("elsewhere"), List.of(line("widget", 6)), TTL));
        Till till = Till.builder(interfering).clock(clock).build();

        Till.BatchResult result = till.executeAll(List.of(
                call(new Command.Reserve(key("k1"), rid("r1"), List.of(line("widget", 3)), TTL)),
                call(new Command.Reserve(key("k2"), rid("r2"), List.of(line("widget", 3)), TTL))));

        assertEquals(1, result.conflicts());
        assertEquals(2, interfering.batchLoads, "loaded again after the refusal");
        // Decided again against four available: r1 takes three, and r2 finds one.
        assertInstanceOf(Outcome.Reserved.class, result.answers().get(0).outcome());
        assertEquals(
                List.of(new Outcome.Shortfall(sku("widget"), 3, 1)),
                ((Outcome.Rejected) result.answers().get(1).outcome()).shortfalls());
        assertEquals(9, ledger.stock(sku("widget")).orElseThrow().reserved());
    }

    @Test
    @DisplayName("when the attempts run out every command that wrote is told so, and a replay is still answered")
    void exhaustedAttemptsAnswerEveryWriterWithAConflict() {
        stock("widget", 10);
        Till.builder(ledger).clock(clock).build().reserve(key("done"), rid("done"), List.of(line("widget", 1)), TTL);
        Refusing refusing = new Refusing(ledger);
        Till till = Till.builder(refusing).clock(clock).maxAttempts(3).build();
        Command fresh = new Command.Reserve(key("k1"), rid("r1"), List.of(line("widget", 1)), TTL);

        Till.BatchResult result = till.executeAll(List.of(
                call(new Command.Reserve(key("done"), rid("again"), List.of(line("widget", 1)), TTL)),
                call(fresh)));

        assertEquals(new Outcome.Reserved(rid("done"), List.of(line("widget", 1)), T0.plus(TTL)), result.answers().get(0).outcome());
        ConflictException conflict = assertInstanceOf(ConflictException.class, result.answers().get(1).failure());
        assertSame(fresh, conflict.command());
        assertEquals(3, conflict.attempts());
        assertEquals(3, result.conflicts());
        assertEquals(3, refusing.refused);
    }

    @Test
    @DisplayName("a load that fails answers every command of its run with that failure")
    void aFailedLoadAnswersEveryCommand() {
        IllegalStateException down = new IllegalStateException("the database is down");
        Till till = Till.builder(new FailingLoad(ledger, down)).clock(clock).build();

        List<Till.Answer> answers = till.executeAll(List.of(
                call(new Command.Reserve(key("k1"), rid("r1"), List.of(line("widget", 1)), TTL)),
                call(new Command.Adjust(key("k2"), sku("widget"), 1)))).answers();

        assertSame(down, answers.get(0).failure());
        assertSame(down, answers.get(1).failure());
    }

    @Test
    @DisplayName("a command the batch's snapshot does not cover is refused on its own, and the rest are decided")
    void aCommandTheSnapshotMissedIsRefusedAlone() {
        stock("widget", 10);
        stock("gadget", 10);
        // An adapter that forgot gadget: a bug, and it must not read as a SKU that was never stocked.
        Forgetting forgetting = new Forgetting(ledger, sku("gadget"));
        Till till = Till.builder(forgetting).clock(clock).build();

        List<Till.Answer> answers = till.executeAll(List.of(
                call(new Command.Reserve(key("k1"), rid("r1"), List.of(line("gadget", 1)), TTL)),
                call(new Command.Reserve(key("k2"), rid("r2"), List.of(line("widget", 1)), TTL)))).answers();

        assertInstanceOf(IncompleteSnapshotException.class, answers.get(0).failure());
        assertInstanceOf(Outcome.Reserved.class, answers.get(1).outcome());
        assertEquals(0, ledger.stock(sku("gadget")).orElseThrow().reserved());
    }

    @Test
    @DisplayName("a batch with nothing to write writes nothing, and an empty one reads nothing")
    void nothingToWriteIsNotWritten() {
        stock("widget", 10);
        Till.builder(ledger).clock(clock).build().reserve(key("done"), rid("done"), List.of(line("widget", 1)), TTL);
        Till till = Till.builder(recording).clock(clock).build();
        recording.calls.clear();

        assertEquals(List.of(), till.executeAll(List.of()).answers());
        till.executeAll(List.of(call(new Command.Reserve(key("done"), rid("again"), List.of(line("widget", 1)), TTL))));

        assertEquals(List.of("loadBatch done"), recording.calls, "a replay writes nothing, so nothing is sent");
    }

    @Test
    @DisplayName("an answer is an outcome or a failure, and getting it is getting the outcome or having the failure thrown")
    void anAnswerIsAnOutcomeOrAFailure() {
        Outcome released = new Outcome.Released(rid("r1"), T0);
        IllegalStateException failure = new IllegalStateException("no");

        assertSame(released, Till.Answer.of(released).get());
        assertNull(Till.Answer.of(released).failure());
        IllegalStateException thrown =
                assertThrows(IllegalStateException.class, () -> Till.Answer.failed(failure).get());
        assertSame(failure, thrown);
        assertThrows(
                IllegalArgumentException.class, () -> new Till.Answer(released, failure), "not both");
        assertThrows(
                IllegalArgumentException.class, () -> new Till.Answer(null, null), "not neither");
    }

    private void stock(String sku, long units) {
        Till.builder(ledger).clock(clock).build().adjust(key("stock-" + sku), sku(sku), units);
    }

    private static Till.Call call(Command command) {
        return Till.Call.of(command);
    }

    /** What a command is called in a recorded call: its key, or "sweep". */
    private static String name(Command command) {
        return command.idempotencyKey().map(IdempotencyKey::value).orElse("sweep");
    }

    /** A clock a test moves by hand. */
    private static final class SteppingClock extends Clock {
        private Instant now;

        private SteppingClock(Instant start) {
            this.now = start;
        }

        private void advance(Duration by) {
            now = now.plus(by);
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

    /** A ledger that passes everything through; the ones below change one thing each. */
    private static class Forwarding implements Ledger {
        final Ledger inner;

        Forwarding(Ledger inner) {
            this.inner = inner;
        }

        @Override
        public Snapshot load(Command command, Instant now, int reclaimLimit) {
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

    /** Writes down every call, in order, naming each command by its key. */
    private static final class Recording extends Forwarding {
        private final List<String> calls = new ArrayList<>();

        Recording(Ledger inner) {
            super(inner);
        }

        @Override
        public Snapshot load(Command command, Instant now, int reclaimLimit) {
            calls.add("load " + name(command) + " " + reclaimLimit);
            return super.load(command, now, reclaimLimit);
        }

        @Override
        public boolean apply(Decision decision) {
            calls.add("apply");
            return super.apply(decision);
        }

        @Override
        public BatchSnapshot loadBatch(List<Command> commands) {
            calls.add("loadBatch " + String.join(" ", commands.stream().map(TillBatchTest::name).toList()));
            return super.loadBatch(commands);
        }

        @Override
        public boolean applyBatch(BatchDecision decision) {
            calls.add("applyBatch");
            return super.applyBatch(decision);
        }
    }

    /** Moves the clock on by a fixed amount whenever a batch is loaded, as a slow load would. */
    private static final class Advancing extends Forwarding {
        private final SteppingClock clock;
        private final Duration by;
        private int batchLoads;

        Advancing(Ledger inner, SteppingClock clock, Duration by) {
            super(inner);
            this.clock = clock;
            this.by = by;
        }

        @Override
        public BatchSnapshot loadBatch(List<Command> commands) {
            batchLoads++;
            BatchSnapshot snapshot = super.loadBatch(commands);
            clock.advance(by);
            return snapshot;
        }
    }

    /** Lets somebody else write once, between the first batch's load and its write. */
    private static final class Interfering extends Forwarding {
        private final Consumer<Ledger> elsewhere;
        private int batchLoads;
        private boolean done;

        Interfering(Ledger inner, Consumer<Ledger> elsewhere) {
            super(inner);
            this.elsewhere = elsewhere;
        }

        @Override
        public BatchSnapshot loadBatch(List<Command> commands) {
            batchLoads++;
            return super.loadBatch(commands);
        }

        @Override
        public boolean applyBatch(BatchDecision decision) {
            if (!done) {
                done = true;
                elsewhere.accept(inner);
            }
            return super.applyBatch(decision);
        }
    }

    /** Refuses every batch write, as a row that never stops moving would. */
    private static final class Refusing extends Forwarding {
        private int refused;

        Refusing(Ledger inner) {
            super(inner);
        }

        @Override
        public boolean applyBatch(BatchDecision decision) {
            refused++;
            return false;
        }
    }

    /** Fails every batch load. */
    private static final class FailingLoad extends Forwarding {
        private final RuntimeException failure;

        FailingLoad(Ledger inner, RuntimeException failure) {
            super(inner);
            this.failure = failure;
        }

        @Override
        public BatchSnapshot loadBatch(List<Command> commands) {
            throw failure;
        }
    }

    /** Leaves one SKU out of every batch snapshot, as an adapter with a bug in its statement would. */
    private static final class Forgetting extends Forwarding {
        private final Sku forgotten;

        Forgetting(Ledger inner, Sku forgotten) {
            super(inner);
            this.forgotten = forgotten;
        }

        @Override
        public BatchSnapshot loadBatch(List<Command> commands) {
            BatchSnapshot full = super.loadBatch(commands);
            Map<Sku, List<StockShard>> stock = new LinkedHashMap<>(full.stock());
            stock.remove(forgotten);
            return new BatchSnapshot(stock, full.reservations(), full.records());
        }
    }
}
