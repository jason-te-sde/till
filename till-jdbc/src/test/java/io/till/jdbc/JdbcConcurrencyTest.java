package io.till.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.till.core.IdempotencyKey;
import io.till.core.Line;
import io.till.core.Outcome;
import io.till.core.RejectionCode;
import io.till.core.ReservationId;
import io.till.core.Sku;
import io.till.core.StockItem;
import io.till.core.Till;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Real threads against a real database.
 *
 * <p>The simulator covers interleavings far more thoroughly, because it chooses them. What it cannot
 * cover is PostgreSQL: whether the conditional update really is atomic, whether the unique constraint
 * really does refuse the second insert, whether read committed really does show the other
 * transaction's commit. This is the flash sale, at the only layer where those questions have answers.
 */
// Suites that share the one database run one at a time. Everything else in the build still runs in
// parallel; these truncate the tables they are about, a truncate locks the whole table, and two of
// them at once is a deadlock rather than a race.
@ResourceLock("till-database")
@ExtendWith(RequiresDatabase.class)
class JdbcConcurrencyTest {

    private static final Duration TTL = Duration.ofMinutes(15);

    private JdbcLedger ledger;
    private Till till;

    @BeforeEach
    void setUp() {
        PostgresFixture.reset();
        ledger = new JdbcLedger(PostgresFixture.dataSource());
        till = Till.builder(ledger).maxAttempts(200).build();
    }

    @Test
    @DisplayName("two hundred callers, twenty units, twenty sales")
    void neverOversells() throws Exception {
        till.adjust(key("seed"), sku("widget"), 20);

        int callers = 200;
        AtomicInteger reserved = new AtomicInteger();
        AtomicInteger refused = new AtomicInteger();

        inParallel(
                callers,
                i -> {
                    Outcome outcome =
                            till.reserve(key("caller-" + i), rid("r-" + i), List.of(Line.of("widget", 1)), TTL);
                    if (outcome.ok()) {
                        reserved.incrementAndGet();
                    } else {
                        assertEquals(RejectionCode.INSUFFICIENT_STOCK, ((Outcome.Rejected) outcome).code());
                        refused.incrementAndGet();
                    }
                });

        assertEquals(20, reserved.get(), "exactly the stock that existed");
        assertEquals(180, refused.get());
        StockItem item = ledger.allStock().get(0);
        assertEquals(20, item.reserved());
        assertEquals(0, item.available());
        assertEquals(20, ledger.allReservations().size());
    }

    @Test
    @DisplayName("the same request sent thirty times at once is executed once")
    void duplicatesExecuteOnce() throws Exception {
        till.adjust(key("seed"), sku("widget"), 100);

        int copies = 30;
        List<Outcome> outcomes = new ArrayList<>();
        List<Future<Outcome>> futures = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(16)) {
            CountDownLatch start = new CountDownLatch(1);
            for (int i = 0; i < copies; i++) {
                int copy = i;
                futures.add(
                        pool.submit(
                                () -> {
                                    start.await();
                                    // One key, a different reservation id per copy: what a stateless
                                    // service behind a load balancer actually sends when a client
                                    // retries a timeout.
                                    return till.reserve(
                                            key("checkout-8123"),
                                            rid("r-" + copy),
                                            List.of(Line.of("widget", 1)),
                                            TTL);
                                }));
            }
            start.countDown();
            for (Future<Outcome> future : futures) {
                outcomes.add(future.get(60, TimeUnit.SECONDS));
            }
        }

        assertEquals(1, outcomes.stream().distinct().count(), "copies disagreed: " + outcomes);
        assertEquals(99, ledger.allStock().get(0).available());
        assertEquals(1, ledger.allReservations().size());
    }

    @Test
    @DisplayName("holds, commits and cancellations at once keep the books balanced")
    void mixedTrafficConserves() throws Exception {
        till.adjust(key("seed"), sku("widget"), 400);

        inParallel(
                80,
                i -> {
                    ReservationId id = rid("r-" + i);
                    if (!till.reserve(key("hold-" + i), id, List.of(Line.of("widget", 2)), TTL).ok()) {
                        return;
                    }
                    if (i % 2 == 0) {
                        till.commit(key("pay-" + i), id);
                    } else {
                        till.release(key("cancel-" + i), id);
                    }
                });

        long committed =
                ledger.allReservations().stream()
                        .filter(r -> r.state() == io.till.core.ReservationState.COMMITTED)
                        .mapToLong(r -> r.lines().get(0).quantity())
                        .sum();
        StockItem item = ledger.allStock().get(0);
        assertEquals(400 - committed, item.onHand());
        assertEquals(0, item.reserved());
        assertTrue(committed > 0, "nothing was sold, so nothing was proved");
    }

    /**
     * Runs callers against a starting gun.
     *
     * Without it, a pool drains its queue almost in order on a loaded machine and the contention
     * this suite exists to create never happens.
     */
    private static void inParallel(int callers, java.util.function.IntConsumer work) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(16)) {
            for (int i = 0; i < callers; i++) {
                int caller = i;
                futures.add(
                        pool.submit(
                                () -> {
                                    start.await();
                                    work.accept(caller);
                                    return null;
                                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get(120, TimeUnit.SECONDS);
            }
        }
    }

    private static Sku sku(String value) {
        return Sku.of(value);
    }

    private static IdempotencyKey key(String value) {
        return IdempotencyKey.of(value);
    }

    private static ReservationId rid(String value) {
        return ReservationId.of(value);
    }
}
