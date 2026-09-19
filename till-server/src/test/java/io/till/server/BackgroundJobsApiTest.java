package io.till.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micrometer.core.instrument.MeterRegistry;
import io.till.client.TillClient;
import io.till.core.EventPublisher;
import io.till.core.IdempotencyKey;
import io.till.core.Line;
import io.till.core.Outcome;
import io.till.core.OutboxEntry;
import io.till.core.ReservationState;
import io.till.core.Sku;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestPropertySource;

/**
 * The sweeper and the publisher, driven by hand.
 *
 * <p>Both are enabled here and their intervals are long, so that the scheduler cannot run one in the
 * middle of an assertion. What is being tested is what each does when it runs, not that Spring can
 * schedule a method.
 */
@Import(BackgroundJobsApiTest.Collecting.class)
@TestPropertySource(
        properties = {
            "till.sweeper.enabled=true",
            "till.outbox.enabled=true",
            "till.sweeper.interval=1h",
            "till.outbox.interval=1h"
        })
// JUnit does not inherit @ResourceLock from a superclass, so every class that shares the one
// database names the lock itself. Without it two Spring contexts write the same tables at once and
// each sees the other's rows.
@ResourceLock("till-database")
class BackgroundJobsApiTest extends ApiTestBase {

    @Autowired
    ExpirySweeper sweeper;

    @Autowired
    OutboxPublisher publisher;

    @Autowired
    CollectingPublisher collected;

    @Autowired
    MeterRegistry meters;

    /**
     * The publisher and the registry are singletons in a context that outlives any one test, so
     * both are emptied here. Asserting on a total that includes the previous test's traffic is how
     * a suite ends up with assertions nobody can change.
     */
    @BeforeEach
    void forgetEarlierTraffic() {
        collected.forget();
        meters.clear();
    }

    @Test
    @DisplayName("the sweeper writes off holds that ran out, and nothing else")
    void sweeperWritesOffExpiredHolds() {
        TillClient till = client(ADMIN_TOKEN);
        till.adjust(IdempotencyKey.of("d1"), Sku.of("widget"), 20);
        Outcome.Reserved stale =
                till.reserve(IdempotencyKey.of("c1"), List.of(Line.of("widget", 5)), Duration.ofMinutes(1));
        Outcome.Reserved live =
                till.reserve(IdempotencyKey.of("c2"), List.of(Line.of("widget", 5)), Duration.ofHours(2));
        assertEquals(10, till.stock(Sku.of("widget")).available());

        clock.advance(Duration.ofMinutes(2));
        sweeper.sweep();

        assertEquals(ReservationState.EXPIRED, till.reservation(stale.id()).state());
        assertEquals(ReservationState.HELD, till.reservation(live.id()).state());
        assertEquals(15, till.stock(Sku.of("widget")).available(), "five came back, five are still held");
    }

    @Test
    @DisplayName("sweeping when there is nothing to sweep changes nothing")
    void sweepingNothing() {
        sweeper.sweep();
        sweeper.sweep();

        assertTrue(ledger.allEvents().isEmpty());
    }

    @Test
    @DisplayName("the publisher drains the outbox in order and marks what it delivered")
    void publisherDrainsInOrder() {
        TillClient till = client(ADMIN_TOKEN);
        till.adjust(IdempotencyKey.of("d1"), Sku.of("widget"), 20);
        Outcome.Reserved held = till.reserve(IdempotencyKey.of("c1"), List.of(Line.of("widget", 2)), null);
        till.commit(IdempotencyKey.of("pay-1"), held.id());

        assertEquals(3, ledger.backlog());
        publisher.drain();

        assertEquals(
                List.of("adjusted:d1", "reserved:" + held.id(), "committed:" + held.id()),
                collected.delivered());
        assertEquals(0, ledger.backlog(), "and it will not deliver them again");

        publisher.drain();
        assertEquals(3, collected.delivered().size());
    }

    @Test
    @DisplayName("a publisher that fails keeps the batch, so delivery is at least once")
    void aFailedBatchIsOfferedAgain() {
        TillClient till = client(ADMIN_TOKEN);
        till.adjust(IdempotencyKey.of("d1"), Sku.of("widget"), 20);

        collected.failNext();
        publisher.drain();

        assertEquals(1, ledger.backlog(), "nothing was marked, so it comes round again");
        assertTrue(collected.delivered().isEmpty());

        publisher.drain();
        assertEquals(List.of("adjusted:d1"), collected.delivered());
    }

    @Test
    @DisplayName("the outcomes an operator cares about are counted by kind, not by status code")
    void metricsAreAboutOutcomes() {
        TillClient till = client(ADMIN_TOKEN);
        till.adjust(IdempotencyKey.of("d1"), Sku.of("widget"), 1);
        till.reserve(IdempotencyKey.of("c1"), List.of(Line.of("widget", 1)), null);
        try {
            till.reserve(IdempotencyKey.of("c2"), List.of(Line.of("widget", 1)), null);
        } catch (RuntimeException expected) {
            // The refusal is the point.
        }

        assertEquals(
                1,
                meters.counter("till.outcome", "kind", "reserve", "outcome", "reserved").count(),
                1e-9);
        assertEquals(
                1,
                meters.counter("till.outcome", "kind", "reserve", "outcome", "insufficient_stock").count(),
                1e-9);
        assertTrue(meters.timer("till.command", "kind", "reserve").count() >= 2);
    }

    /** Replaces the logging publisher with one the tests can read and break. */
    @TestConfiguration
    static class Collecting {

        @Bean
        @Primary
        CollectingPublisher collectingPublisher() {
            return new CollectingPublisher();
        }
    }

    /** Remembers what it was given, and fails on demand. */
    static final class CollectingPublisher implements EventPublisher {

        private final List<String> delivered = new ArrayList<>();
        private boolean failNext;

        @Override
        public synchronized void publish(List<OutboxEntry> entries) {
            if (failNext) {
                failNext = false;
                throw new IllegalStateException("the broker is down");
            }
            entries.forEach(entry -> delivered.add(entry.dedupeKey()));
        }

        synchronized void failNext() {
            failNext = true;
        }

        synchronized List<String> delivered() {
            return List.copyOf(delivered);
        }

        synchronized void forget() {
            delivered.clear();
            failNext = false;
        }
    }
}
