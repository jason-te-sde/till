package io.till.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.till.core.Command;
import io.till.core.DeadlineExceededException;
import io.till.core.Decision;
import io.till.core.IdempotencyKey;
import io.till.core.Ledger;
import io.till.core.Line;
import io.till.core.Outcome;
import io.till.core.ReservationId;
import io.till.core.Sku;
import io.till.core.Snapshot;
import io.till.core.Till;
import io.till.core.mem.InMemoryLedger;
import io.till.jdbc.LedgerException;
import java.sql.SQLTransientConnectionException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The {@code Till-Timeout-Ms} header's parsing, and the two meters it drives.
 *
 * <p>Below the HTTP layer rather than through it, for the case the API tests cannot reach
 * deterministically: {@code till.late} depends on {@code apply} itself outlasting the deadline,
 * and only a clock a test steps from inside a fake {@link Ledger} can put the gap there on demand.
 * {@link ReservationApiTest} and {@link RequestDeadlineApiTest} cover the wire this sits below.
 */
class CommandsTest {

    private static final Instant T0 = Instant.parse("2026-09-10T12:00:00Z");

    @Test
    @DisplayName("no header: behaves exactly as before, and records the ordinary outcome")
    void noHeaderBehavesAsBefore() {
        InMemoryLedger ledger = new InMemoryLedger();
        Till till = Till.builder(ledger).clock(fixed(T0)).build();
        till.adjust(IdempotencyKey.of("d1"), Sku.of("widget"), 10);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        Commands commands = new Commands(till, registry);

        Outcome outcome = commands.run(reserve("c1", "r1", 3));

        assertInstanceOf(Outcome.Reserved.class, outcome);
        assertEquals(1, registry.counter("till.outcome", "kind", "reserve", "outcome", "reserved").count());
        assertTrue(registry.find("till.late").counters().isEmpty(), "no deadline, nothing to be late against");
    }

    @Test
    @DisplayName("a malformed header is rejected before anything runs")
    void malformedHeaderIsRejected() {
        Commands commands =
                new Commands(Till.builder(new InMemoryLedger()).clock(fixed(T0)).build(), new SimpleMeterRegistry());

        IllegalArgumentException notANumber =
                assertThrows(
                        IllegalArgumentException.class, () -> commands.run(reserve("c1", "r1", 1), "not-a-number"));
        assertTrue(notANumber.getMessage().contains("Till-Timeout-Ms"), notANumber.getMessage());

        assertThrows(IllegalArgumentException.class, () -> commands.run(reserve("c2", "r2", 1), "-1"));
    }

    @Test
    @DisplayName("Till-Timeout-Ms: 0 has already passed by the time it is checked")
    void zeroHeaderIsAlreadyExceeded() {
        InMemoryLedger ledger = new InMemoryLedger();
        Till till = Till.builder(ledger).clock(fixed(T0)).build();
        till.adjust(IdempotencyKey.of("d1"), Sku.of("widget"), 10);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        Commands commands = new Commands(till, registry);

        assertThrows(DeadlineExceededException.class, () -> commands.run(reserve("c1", "r1", 1), "0"));

        assertEquals(
                1,
                registry.counter("till.outcome", "kind", "reserve", "outcome", "deadline_exceeded").count());
        assertTrue(ledger.reservation(ReservationId.of("r1")).isEmpty(), "nothing was applied");
    }

    @Test
    @DisplayName("a generous header succeeds and is not counted as late")
    void generousHeaderSucceedsAndIsNotLate() {
        InMemoryLedger ledger = new InMemoryLedger();
        Till till = Till.builder(ledger).clock(fixed(T0)).build();
        till.adjust(IdempotencyKey.of("d1"), Sku.of("widget"), 10);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        Commands commands = new Commands(till, registry);

        Outcome outcome = commands.run(reserve("c1", "r1", 3), "60000");

        assertInstanceOf(Outcome.Reserved.class, outcome);
        assertTrue(registry.find("till.late").counters().isEmpty());
    }

    @Test
    @DisplayName("apply() itself running past the deadline is applied and counted as late, not refused")
    void applyRunningPastTheDeadlineIsLate() {
        InMemoryLedger ledger = new InMemoryLedger();
        Till.builder(ledger).clock(fixed(T0)).build().adjust(IdempotencyKey.of("d1"), Sku.of("widget"), 10);

        SteppingClock clock = new SteppingClock(T0);
        // What Till.execute's pre-apply check cannot see: apply() itself taking two seconds,
        // longer than the one-second budget below. This is the gap ADR 11 measures with
        // till.late instead of closing, because closing it means the Ledger checking the deadline
        // inside its write transaction.
        Ledger slowApply =
                new Ledger() {
                    @Override
                    public Snapshot load(Command command, Instant now, int reclaimLimit) {
                        return ledger.load(command, now, reclaimLimit);
                    }

                    @Override
                    public boolean apply(Decision decision) {
                        boolean applied = ledger.apply(decision);
                        clock.advance(Duration.ofSeconds(2));
                        return applied;
                    }
                };
        Till till = Till.builder(slowApply).clock(clock).build();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        Commands commands = new Commands(till, registry);

        Outcome outcome = commands.run(reserve("c1", "r1", 3), "1000");

        assertInstanceOf(Outcome.Reserved.class, outcome, "applied in time; only reported late afterwards");
        assertEquals(
                1, registry.counter("till.late", "kind", "reserve", "outcome", "reserved").count());
    }

    @Test
    @DisplayName("the pool exhausted is counted as overloaded, next to exhausted and deadline_exceeded")
    void poolExhaustionIsCountedAsOverloaded() {
        SQLTransientConnectionException exhausted =
                new SQLTransientConnectionException(
                        "till - Connection is not available, request timed out after 2000ms "
                                + "(total=16, active=16, idle=0, waiting=184)");
        Ledger refusing =
                new Ledger() {
                    @Override
                    public Snapshot load(Command command, Instant now, int reclaimLimit) {
                        throw new LedgerException("loading a snapshot for " + command, exhausted);
                    }

                    @Override
                    public boolean apply(Decision decision) {
                        throw new IllegalStateException("not reached: load always fails first");
                    }
                };
        Till till = Till.builder(refusing).clock(fixed(T0)).build();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        Commands commands = new Commands(till, registry);

        assertThrows(LedgerException.class, () -> commands.run(reserve("c1", "r1", 3)));

        assertEquals(
                1, registry.counter("till.outcome", "kind", "reserve", "outcome", "overloaded").count());
    }

    @Test
    @DisplayName("any other LedgerException is not counted as overloaded, and still propagates unchanged")
    void anOrdinaryLedgerFailureIsNotCountedAsOverloaded() {
        LedgerException original =
                new LedgerException("loading a snapshot", new java.sql.SQLException("connection refused"));
        Ledger broken =
                new Ledger() {
                    @Override
                    public Snapshot load(Command command, Instant now, int reclaimLimit) {
                        throw original;
                    }

                    @Override
                    public boolean apply(Decision decision) {
                        throw new IllegalStateException("not reached: load always fails first");
                    }
                };
        Till till = Till.builder(broken).clock(fixed(T0)).build();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        Commands commands = new Commands(till, registry);

        LedgerException thrown =
                assertThrows(LedgerException.class, () -> commands.run(reserve("c1", "r1", 3)));

        assertEquals(original, thrown, "the exact same failure, not a wrapped or replaced one");
        assertTrue(registry.find("till.outcome").counters().stream()
                .noneMatch(counter -> "overloaded".equals(counter.getId().getTag("outcome"))));
    }

    private static Command reserve(String key, String id, long quantity) {
        return new Command.Reserve(
                IdempotencyKey.of(key),
                ReservationId.of(id),
                List.of(Line.of("widget", quantity)),
                Duration.ofMinutes(15));
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
}
