package io.till.core;

import static io.till.core.Fixtures.key;
import static io.till.core.Fixtures.line;
import static io.till.core.Fixtures.rid;
import static io.till.core.Fixtures.sku;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.till.core.mem.InMemoryLedger;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Real threads on one SKU.
 *
 * <p>The simulator in {@code till-testkit} covers interleavings far more thoroughly than this can,
 * because it controls the schedule. What it cannot cover is the Java memory model: whether the
 * ledger's own locking actually publishes what it wrote. That is the whole reason these exist.
 *
 * <p>What they deliberately do <b>not</b> assert is that a conflict occurred. It is tempting —
 * "a hundred threads that never conflicted did not exercise the retry loop" — and it is not a
 * property a threaded test can promise. Most of those hundred are refused for want of stock, and a
 * refusal writes no stock row and so has no version to conflict on; the contending writers are only
 * the handful that succeed. Asserted here, it failed once in a full build. The property is real and
 * is asserted where it is deterministic: {@code InMemoryLedgerTest.refusesAStaleVersion} constructs
 * the conflict by hand, and {@code SimTest.theRunWasHostile} requires thousands of them from a seed.
 */
class ConcurrencyTest {

    private static final Duration TTL = Duration.ofMinutes(15);

    @Test
    @DisplayName("a hundred callers racing for ten units get ten units, not eleven")
    void neverOversells() throws Exception {
        InMemoryLedger ledger = new InMemoryLedger();
        Till till = Till.builder(ledger).maxAttempts(1000).build();
        till.adjust(key("seed"), sku("widget"), 10);

        int callers = 100;
        AtomicInteger reserved = new AtomicInteger();
        AtomicInteger refused = new AtomicInteger();

        run(
                callers,
                i ->
                        () -> {
                            Outcome outcome =
                                    till.reserve(
                                            key("caller-" + i), rid("r-" + i), List.of(line("widget", 1)), TTL);
                            if (outcome.ok()) {
                                reserved.incrementAndGet();
                            } else {
                                assertEquals(RejectionCode.INSUFFICIENT_STOCK, ((Outcome.Rejected) outcome).code());
                                refused.incrementAndGet();
                            }
                            return null;
                        });

        assertEquals(10, reserved.get(), "exactly the stock that existed");
        assertEquals(90, refused.get());
        StockItem item = ledger.stock(sku("widget")).orElseThrow();
        assertEquals(10, item.reserved());
        assertEquals(0, item.available());
        assertEquals(10, ledger.allReservations().size(), "and one reservation per unit that went");
    }

    @Test
    @DisplayName("sixty-four writers on one row all succeed, whatever it takes in retries")
    void contendsWithoutLosingAWrite() throws Exception {
        InMemoryLedger ledger = new InMemoryLedger();
        Till till = Till.builder(ledger).maxAttempts(1000).build();
        till.adjust(key("seed"), sku("widget"), 500);

        int callers = 64;
        AtomicInteger reserved = new AtomicInteger();

        // Plenty of stock, so every caller succeeds and every one of them writes the same row. This
        // is the shape where the retry loop earns its place: the lost-update bug would show up here
        // as a reserved count below the number of holds.
        run(
                callers,
                i ->
                        () -> {
                            if (till.reserve(key("c-" + i), rid("r-" + i), List.of(line("widget", 3)), TTL).ok()) {
                                reserved.incrementAndGet();
                            }
                            return null;
                        });

        assertEquals(callers, reserved.get(), "every caller should have got its hold");
        assertEquals(callers * 3, ledger.stock(sku("widget")).orElseThrow().reserved());
        assertEquals(callers, ledger.allReservations().size());
    }

    @Test
    @DisplayName("the same key sent twenty times at once is executed once")
    void concurrentDuplicatesExecuteOnce() throws Exception {
        InMemoryLedger ledger = new InMemoryLedger();
        Till till = Till.builder(ledger).maxAttempts(1000).build();
        till.adjust(key("seed"), sku("widget"), 100);

        int copies = 20;
        List<Outcome> outcomes = new ArrayList<>();
        List<Future<Outcome>> futures = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(copies)) {
            CountDownLatch start = new CountDownLatch(1);
            for (int i = 0; i < copies; i++) {
                // Every copy carries the same key and, as a stateless server would, a different
                // reservation id.
                int copy = i;
                futures.add(
                        pool.submit(
                                () -> {
                                    start.await();
                                    return till.reserve(
                                            key("checkout-8123"),
                                            rid("r-" + copy),
                                            List.of(line("widget", 1)),
                                            TTL);
                                }));
            }
            start.countDown();
            for (Future<Outcome> future : futures) {
                outcomes.add(future.get(30, TimeUnit.SECONDS));
            }
        }

        assertEquals(1, outcomes.stream().distinct().count(), "every copy got the same answer: " + outcomes);
        assertEquals(99, ledger.stock(sku("widget")).orElseThrow().available(), "one unit went, not twenty");
        assertEquals(
                1,
                ledger.allReservations().size(),
                "and there is one reservation, whichever copy happened to win");
    }

    @Test
    @DisplayName("commits and releases racing on the same holds keep the books balanced")
    void mixedTrafficConserves() throws Exception {
        InMemoryLedger ledger = new InMemoryLedger();
        Till till = Till.builder(ledger).maxAttempts(1000).build();
        till.adjust(key("seed"), sku("widget"), 500);

        int callers = 60;
        run(
                callers,
                i ->
                        () -> {
                            ReservationId id = rid("r-" + i);
                            Outcome held = till.reserve(key("hold-" + i), id, List.of(line("widget", 2)), TTL);
                            if (!held.ok()) {
                                return null;
                            }
                            if (i % 2 == 0) {
                                till.commit(key("pay-" + i), id);
                            } else {
                                till.release(key("cancel-" + i), id);
                            }
                            return null;
                        });

        StockItem item = ledger.stock(sku("widget")).orElseThrow();
        long committed =
                ledger.allReservations().stream()
                        .filter(r -> r.state() == ReservationState.COMMITTED)
                        .mapToLong(r -> r.lines().get(0).quantity())
                        .sum();
        assertEquals(500 - committed, item.onHand(), "on-hand fell by exactly what was committed");
        assertEquals(0, item.reserved(), "and every hold reached a terminal state");
    }

    /**
     * Runs callers against a starting gun.
     *
     * The latch is not decoration. Submitting tasks to a pool and hoping they overlap works on an
     * idle machine and stops working on a loaded one — the pool drains them almost in order, the
     * retry loop is never exercised, and the assertion that it was fails. Holding every thread until
     * they are all waiting makes the contention happen rather than hoping for it.
     */
    private static void run(int callers, java.util.function.IntFunction<Callable<Void>> work) throws Exception {
        int threads = Math.min(callers, 64);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Void>> futures = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            for (int i = 0; i < callers; i++) {
                Callable<Void> task = work.apply(i);
                futures.add(
                        pool.submit(
                                () -> {
                                    start.await();
                                    return task.call();
                                }));
            }
            start.countDown();
            for (Future<Void> future : futures) {
                future.get(60, TimeUnit.SECONDS);
            }
        }
    }
}
