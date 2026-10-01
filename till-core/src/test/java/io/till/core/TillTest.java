package io.till.core;

import static io.till.core.Fixtures.TTL;
import static io.till.core.Fixtures.key;
import static io.till.core.Fixtures.line;
import static io.till.core.Fixtures.rid;
import static io.till.core.Fixtures.sku;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.till.core.mem.InMemoryLedger;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TillTest {

    private static final Instant T0 = Fixtures.T0;

    @Test
    @DisplayName("the loop retries a conflict and succeeds")
    void retriesAConflict() {
        InMemoryLedger ledger = new InMemoryLedger();
        RefusingLedger refusing = new RefusingLedger(ledger, 3);
        Till till = Till.builder(refusing).clock(fixed(T0)).build();
        seed(ledger, 10);

        Outcome outcome = till.reserve(key("k1"), rid("r1"), List.of(line("widget", 3)), TTL);

        assertInstanceOf(Outcome.Reserved.class, outcome);
        assertEquals(4, refusing.attempts.get(), "three refusals and then a success");
    }

    @Test
    @DisplayName("the loop gives up rather than spinning forever")
    void givesUpEventually() {
        InMemoryLedger ledger = new InMemoryLedger();
        RefusingLedger refusing = new RefusingLedger(ledger, Integer.MAX_VALUE);
        Till till = Till.builder(refusing).clock(fixed(T0)).maxAttempts(4).build();
        seed(ledger, 10);

        ConflictException thrown =
                assertThrows(
                        ConflictException.class,
                        () -> till.reserve(key("k1"), rid("r1"), List.of(line("widget", 3)), TTL));

        assertEquals(4, thrown.attempts());
        assertEquals(4, refusing.attempts.get(), "and it stopped there rather than trying a fifth time");
    }

    @Test
    @DisplayName("a replay never opens a transaction")
    void replayDoesNotApply() {
        InMemoryLedger ledger = new InMemoryLedger();
        CountingLedger counting = new CountingLedger(ledger);
        Till till = Till.builder(counting).clock(fixed(T0)).build();
        seed(ledger, 10);
        counting.applies.set(0);

        till.reserve(key("k1"), rid("r1"), List.of(line("widget", 3)), TTL);
        assertEquals(1, counting.applies.get());

        till.reserve(key("k1"), rid("r2"), List.of(line("widget", 3)), TTL);
        assertEquals(1, counting.applies.get(), "the second call had nothing to write");
    }

    @Test
    @DisplayName("a sweep that finds nothing never opens a transaction")
    void emptySweepDoesNotApply() {
        InMemoryLedger ledger = new InMemoryLedger();
        CountingLedger counting = new CountingLedger(ledger);
        Till till = Till.builder(counting).clock(fixed(T0)).build();

        assertEquals(0, till.sweep(100));
        assertEquals(0, counting.applies.get());
    }

    @Test
    @DisplayName("the whole path: stock in, held, committed")
    void theHappyPath() {
        InMemoryLedger ledger = new InMemoryLedger();
        Till till = Till.builder(ledger).clock(fixed(T0)).build();

        till.adjust(key("delivery-41"), sku("widget"), 100);
        till.reserve(key("checkout-1"), rid("r1"), List.of(line("widget", 2)), TTL);

        assertEquals(100, ledger.stock(sku("widget")).orElseThrow().onHand());
        assertEquals(98, ledger.stock(sku("widget")).orElseThrow().available());

        till.commit(key("pay-1"), rid("r1"));

        StockItem after = ledger.stock(sku("widget")).orElseThrow();
        assertEquals(98, after.onHand(), "the goods left");
        assertEquals(0, after.reserved());
        assertEquals(ReservationState.COMMITTED, ledger.reservation(rid("r1")).orElseThrow().state());
    }

    @Test
    @DisplayName("a hold that runs out returns its stock without anything having to notice")
    void holdsExpireOnTheirOwn() {
        InMemoryLedger ledger = new InMemoryLedger();
        SteppingClock clock = new SteppingClock(T0);
        Till till = Till.builder(ledger).clock(clock).build();

        till.adjust(key("d1"), sku("widget"), 10);
        till.reserve(key("c1"), rid("r1"), List.of(line("widget", 10)), Duration.ofMinutes(15));
        assertEquals(0, ledger.stock(sku("widget")).orElseThrow().available());

        clock.advance(Duration.ofMinutes(16));

        // No sweep has run. The next caller still gets the stock, because the deadline is the truth.
        Outcome outcome = till.reserve(key("c2"), rid("r2"), List.of(line("widget", 10)), Duration.ofMinutes(15));

        assertInstanceOf(Outcome.Reserved.class, outcome);
        assertEquals(ReservationState.EXPIRED, ledger.reservation(rid("r1")).orElseThrow().state());
    }

    @Test
    @DisplayName("instants are truncated to what a database can store")
    void instantsAreTruncatedToMicroseconds() {
        InMemoryLedger ledger = new InMemoryLedger();
        Till till = Till.builder(ledger).clock(fixed(Instant.parse("2026-09-10T12:00:00.123456789Z"))).build();

        till.adjust(key("d1"), sku("widget"), 10);
        Outcome outcome = till.reserve(key("c1"), rid("r1"), List.of(line("widget", 1)), TTL);

        Instant expires = ((Outcome.Reserved) outcome).expiresAt();
        assertEquals(
                expires.truncatedTo(java.time.temporal.ChronoUnit.MICROS),
                expires,
                "PostgreSQL stores microseconds; an instant that loses precision on the way to disk "
                        + "comes back as a different value than the one the caller was told");
    }

    @Test
    @DisplayName("a command with stock to spare leaves the expired holds to the sweeper")
    void plentyLeavesExpiredHoldsAlone() {
        InMemoryLedger ledger = new InMemoryLedger();
        SteppingClock clock = new SteppingClock(T0);
        RecordingLedger recording = new RecordingLedger(ledger);
        Till till = Till.builder(recording).clock(clock).build();
        seed(ledger, 10);
        till.reserve(key("k1"), rid("abandoned"), List.of(line("widget", 3)), TTL);
        clock.advance(TTL.plusSeconds(1));
        recording.limits.clear();

        Outcome outcome = till.reserve(key("k2"), rid("r2"), List.of(line("widget", 3)), TTL);

        assertInstanceOf(Outcome.Reserved.class, outcome);
        assertEquals(List.of(0), recording.limits, "one load, without the expired holds");
        assertEquals(ReservationState.HELD, ledger.reservation(rid("abandoned")).orElseThrow().state(), "left for the sweeper");
    }

    @Test
    @DisplayName("a command short of stock writes off the expired holds in its way, and succeeds")
    void shortfallReclaims() {
        InMemoryLedger ledger = new InMemoryLedger();
        SteppingClock clock = new SteppingClock(T0);
        RecordingLedger recording = new RecordingLedger(ledger);
        Till till = Till.builder(recording).clock(clock).build();
        seed(ledger, 10);
        till.reserve(key("k1"), rid("abandoned"), List.of(line("widget", 10)), TTL);
        clock.advance(TTL.plusSeconds(1));
        recording.limits.clear();

        Outcome outcome = till.reserve(key("k2"), rid("r2"), List.of(line("widget", 4)), TTL);

        assertInstanceOf(Outcome.Reserved.class, outcome, "never refused while an expired hold was in the way");
        assertEquals(List.of(0, Till.DEFAULT_RECLAIM_LIMIT), recording.limits, "a lean load, then one with the expired holds");
        assertEquals(ReservationState.EXPIRED, ledger.reservation(rid("abandoned")).orElseThrow().state());
        assertEquals(4, ledger.stock(sku("widget")).orElseThrow().reserved());
    }

    @Test
    @DisplayName("a shortfall the expired holds cannot make up is still refused")
    void shortfallBeyondTheExpiredHolds() {
        InMemoryLedger ledger = new InMemoryLedger();
        SteppingClock clock = new SteppingClock(T0);
        Till till = Till.builder(ledger).clock(clock).build();
        seed(ledger, 10);
        till.reserve(key("k1"), rid("abandoned"), List.of(line("widget", 3)), TTL);
        clock.advance(TTL.plusSeconds(1));

        Outcome outcome = till.reserve(key("k2"), rid("r2"), List.of(line("widget", 11)), TTL);

        Outcome.Rejected rejected = assertInstanceOf(Outcome.Rejected.class, outcome);
        assertEquals(RejectionCode.INSUFFICIENT_STOCK, rejected.code());
    }

    @Test
    @DisplayName("a sweep brings as many expired holds as it may write off, whatever a passing command's limit")
    void sweepUsesItsOwnLimit() {
        InMemoryLedger ledger = new InMemoryLedger();
        SteppingClock clock = new SteppingClock(T0);
        Till till = Till.builder(ledger).clock(clock).reclaimLimit(2).build();
        seed(ledger, 100);
        for (int i = 0; i < 5; i++) {
            till.reserve(key("k" + i), rid("abandoned-" + i), List.of(line("widget", 1)), TTL);
        }
        clock.advance(TTL.plusSeconds(1));

        assertEquals(5, till.sweep(10), "all five, not the two a passing command would write off");
        assertEquals(0, Till.builder(ledger).clock(clock).reclaimLimit(0).build().sweep(10), "and none left over");
    }

    @Test
    @DisplayName("with reclaiming turned off, a sweep still sweeps")
    void sweepWithReclaimingOff() {
        InMemoryLedger ledger = new InMemoryLedger();
        SteppingClock clock = new SteppingClock(T0);
        Till till = Till.builder(ledger).clock(clock).reclaimLimit(0).build();
        seed(ledger, 10);
        till.reserve(key("k1"), rid("abandoned"), List.of(line("widget", 3)), TTL);
        clock.advance(TTL.plusSeconds(1));

        assertEquals(1, till.sweep(10));
    }

    @Test
    @DisplayName("a bad configuration is refused when the till is built, not when it is used")
    void refusesBadConfiguration() {
        assertThrows(IllegalArgumentException.class, () -> Till.builder(new InMemoryLedger()).maxAttempts(0).build());
        assertThrows(IllegalArgumentException.class, () -> Till.builder(new InMemoryLedger()).reclaimLimit(-1).build());
    }

    @Test
    @DisplayName("execute with a null deadline behaves exactly as execute(Command)")
    void nullDeadlineBehavesAsNoDeadline() {
        InMemoryLedger ledger = new InMemoryLedger();
        Till till = Till.builder(ledger).clock(fixed(T0)).build();
        seed(ledger, 10);

        Outcome outcome =
                till.execute(new Command.Reserve(key("c1"), rid("r1"), List.of(line("widget", 3)), TTL), null);

        assertInstanceOf(Outcome.Reserved.class, outcome);
        assertEquals(3, ledger.stock(sku("widget")).orElseThrow().reserved());
    }

    @Test
    @DisplayName("a deadline already passed: execute loads nothing and applies nothing")
    void alreadyPassedDeadlineLoadsAndAppliesNothing() {
        InMemoryLedger ledger = new InMemoryLedger();
        CountingLedger counting = new CountingLedger(ledger);
        Till till = Till.builder(counting).clock(fixed(T0)).build();
        seed(ledger, 10);
        counting.loads.set(0);

        Command command = new Command.Reserve(key("c1"), rid("r1"), List.of(line("widget", 3)), TTL);
        DeadlineExceededException thrown =
                assertThrows(DeadlineExceededException.class, () -> till.execute(command, T0.minusSeconds(1)));

        assertEquals(command, thrown.command());
        assertEquals(0, counting.loads.get(), "the deadline is checked before the load, not after it");
        assertEquals(0, counting.applies.get());
        assertTrue(ledger.reservation(rid("r1")).isEmpty());
    }

    @Test
    @DisplayName("a deadline that passes between decide and apply: nothing is applied")
    void deadlinePassingBetweenDecideAndApplyAppliesNothing() {
        InMemoryLedger ledger = new InMemoryLedger();
        seed(ledger, 10);
        SteppingClock clock = new SteppingClock(T0);
        // The deadline is still ahead when the attempt starts, but the load for that attempt
        // pushes the clock a second past it — the gap between "decided" and "about to apply" that
        // the second check exists to catch.
        AdvancingOnLoadLedger advancing = new AdvancingOnLoadLedger(ledger, clock, Duration.ofSeconds(1));
        Till till = Till.builder(advancing).clock(clock).build();
        Instant deadline = T0.plusMillis(500);

        Command command = new Command.Reserve(key("c1"), rid("r1"), List.of(line("widget", 3)), TTL);
        assertThrows(DeadlineExceededException.class, () -> till.execute(command, deadline));

        assertTrue(ledger.reservation(rid("r1")).isEmpty(), "decided in time, but applied too late and so not applied");
        assertEquals(10, ledger.stock(sku("widget")).orElseThrow().available(), "nothing was taken");
    }

    private static void seed(InMemoryLedger ledger, long quantity) {
        Till.builder(ledger).clock(fixed(T0)).build().adjust(key("seed"), sku("widget"), quantity);
    }

    private static Clock fixed(Instant instant) {
        return Clock.fixed(instant, ZoneOffset.UTC);
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
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    /** Refuses the first {@code n} applies, so the retry loop can be watched. */
    private static final class RefusingLedger implements Ledger {
        private final Ledger delegate;
        private final int refusals;
        private final AtomicInteger attempts = new AtomicInteger();

        private RefusingLedger(Ledger delegate, int refusals) {
            this.delegate = delegate;
            this.refusals = refusals;
        }

        @Override
        public Snapshot load(Command command, Instant now, int reclaimLimit) {
            return delegate.load(command, now, reclaimLimit);
        }

        @Override
        public boolean apply(Decision decision) {
            return attempts.incrementAndGet() > refusals && delegate.apply(decision);
        }
    }

    /** Records the reclaim limit of every load, so a test can see which snapshot a decision had. */
    private static final class RecordingLedger implements Ledger {
        private final Ledger delegate;
        private final List<Integer> limits = new java.util.ArrayList<>();

        private RecordingLedger(Ledger delegate) {
            this.delegate = delegate;
        }

        @Override
        public Snapshot load(Command command, Instant now, int reclaimLimit) {
            limits.add(reclaimLimit);
            return delegate.load(command, now, reclaimLimit);
        }

        @Override
        public boolean apply(Decision decision) {
            return delegate.apply(decision);
        }
    }

    /** Counts loads and applies, so a test can assert that one never happened. */
    private static final class CountingLedger implements Ledger {
        private final Ledger delegate;
        private final AtomicInteger loads = new AtomicInteger();
        private final AtomicInteger applies = new AtomicInteger();

        private CountingLedger(Ledger delegate) {
            this.delegate = delegate;
        }

        @Override
        public Snapshot load(Command command, Instant now, int reclaimLimit) {
            loads.incrementAndGet();
            return delegate.load(command, now, reclaimLimit);
        }

        @Override
        public boolean apply(Decision decision) {
            applies.incrementAndGet();
            return delegate.apply(decision);
        }
    }

    /**
     * Advances a clock by a fixed amount as a side effect of loading, so a test can put the
     * deadline in the gap between one attempt's decide and its apply without sleeping through it.
     */
    private static final class AdvancingOnLoadLedger implements Ledger {
        private final Ledger delegate;
        private final SteppingClock clock;
        private final Duration by;

        private AdvancingOnLoadLedger(Ledger delegate, SteppingClock clock, Duration by) {
            this.delegate = delegate;
            this.clock = clock;
            this.by = by;
        }

        @Override
        public Snapshot load(Command command, Instant now, int reclaimLimit) {
            Snapshot snapshot = delegate.load(command, now, reclaimLimit);
            clock.advance(by);
            return snapshot;
        }

        @Override
        public boolean apply(Decision decision) {
            return delegate.apply(decision);
        }
    }

    @Test
    @DisplayName("the counting ledger used above really does delegate")
    void countingLedgerIsHonest() {
        InMemoryLedger ledger = new InMemoryLedger();
        CountingLedger counting = new CountingLedger(ledger);
        Till.builder(counting).clock(fixed(T0)).build().adjust(key("d"), sku("widget"), 5);

        assertTrue(ledger.stock(sku("widget")).isPresent());
    }
}
