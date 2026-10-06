package io.till.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.till.core.Allocation;
import io.till.core.BatchDecision;
import io.till.core.BatchSnapshot;
import io.till.core.Command;
import io.till.core.Decision;
import io.till.core.IdempotencyKey;
import io.till.core.Ledger;
import io.till.core.Line;
import io.till.core.Outcome;
import io.till.core.RejectionCode;
import io.till.core.ReservationId;
import io.till.core.ReservationState;
import io.till.core.Sku;
import io.till.core.Snapshot;
import io.till.core.StockItem;
import io.till.core.StockShard;
import io.till.core.Till;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.IntConsumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceLock;

/**
 * Real threads against a real database, with every command decided in a batch (ADR 16).
 *
 * <p>{@link JdbcConcurrencyTest}'s flash sale, with its callers' commands queued and taken in batches
 * by several writers at once — as several ledger tasks, each with a worker of its own, would take them
 * — so that batches race each other for the same rows. One writer cannot race itself; four can, and
 * the versions on every row a batch writes are what has to keep them from selling anything twice.
 */
// Suites that share the one database run one at a time. Everything else in the build still runs in
// parallel; these truncate the tables they are about, a truncate locks the whole table, and two of
// them at once is a deadlock rather than a race.
@ResourceLock("till-database")
@ExtendWith(RequiresDatabase.class)
class JdbcBatchConcurrencyTest {

    private static final Duration TTL = Duration.ofMinutes(15);

    private JdbcLedger ledger;

    @BeforeEach
    void setUp() {
        PostgresFixture.reset();
        ledger = new JdbcLedger(PostgresFixture.dataSource());
    }

    @Test
    @DisplayName("four writers taking two hundred callers in batches, twenty units: twenty sales")
    void neverOversells() throws Exception {
        Till till = Till.builder(ledger).maxAttempts(200).build();
        till.adjust(key("seed"), sku("widget"), 20);
        AtomicInteger reserved = new AtomicInteger();
        AtomicInteger refused = new AtomicInteger();

        try (Writers writers = new Writers(till, 4, 16)) {
            inParallel(200, i -> {
                Outcome outcome = writers.run(new Command.Reserve(key("caller-" + i), rid("r-" + i), List.of(Line.of("widget", 1)), TTL));
                if (outcome.ok()) {
                    reserved.incrementAndGet();
                } else {
                    assertEquals(RejectionCode.INSUFFICIENT_STOCK, ((Outcome.Rejected) outcome).code());
                    refused.incrementAndGet();
                }
            });
            assertTrue(writers.largestBatch() > 1, "the callers were meant to be taken in batches: " + writers.largestBatch());
        }

        assertEquals(20, reserved.get(), "exactly the stock that existed");
        assertEquals(180, refused.get());
        StockItem item = ledger.allStock().get(0);
        assertEquals(20, item.reserved());
        assertEquals(0, item.available());
        assertEquals(20, ledger.allReservations().size());
    }

    @Test
    @DisplayName("split eight ways, batched by four writers, two hundred callers buy exactly the twenty units, each hold in the row it came from")
    void neverOversellsAcrossShards() throws Exception {
        Till till = Till.builder(ledger).maxAttempts(200).build();
        till.adjust(key("seed"), sku("widget"), 20);
        till.shard(key("split"), sku("widget"), 8);

        try (Writers writers = new Writers(till, 4, 16)) {
            inParallel(200, i -> writers.run(
                    new Command.Reserve(key("caller-" + i), rid("r-" + i), List.of(Line.of("widget", i < 190 ? 1 : 2)), TTL)));
        }

        StockItem item = ledger.allStock().get(0);
        assertEquals(20, item.onHand());
        long held = ledger.allReservations().stream().mapToLong(r -> r.lines().get(0).quantity()).sum();
        assertEquals(item.reserved(), held, "every unit reserved belongs to exactly one hold");
        for (StockShard shard : ledger.allShards()) {
            long fromHere = ledger.allReservations().stream()
                    .flatMap(r -> r.allocations().stream())
                    .filter(a -> a.shard() == shard.index())
                    .mapToLong(Allocation::quantity)
                    .sum();
            assertEquals(shard.reserved(), fromHere, "shard " + shard.index() + " reserves what its holds took from it");
        }
        assertTrue(item.available() <= 1, "no hold was refused while it could have been covered: " + item);
    }

    @Test
    @DisplayName("the same request sent thirty times at once, spread over batches and writers, is executed once")
    void duplicatesExecuteOnce() throws Exception {
        Till till = Till.builder(ledger).maxAttempts(200).build();
        till.adjust(key("seed"), sku("widget"), 100);
        List<Outcome> outcomes = new java.util.concurrent.CopyOnWriteArrayList<>();

        try (Writers writers = new Writers(till, 4, 8)) {
            inParallel(30, copy -> outcomes.add(writers.run(
                    new Command.Reserve(key("checkout-8123"), rid("r-" + copy), List.of(Line.of("widget", 1)), TTL))));
        }

        assertEquals(1, outcomes.stream().distinct().count(), "copies disagreed: " + outcomes);
        assertEquals(99, ledger.allStock().get(0).available());
        assertEquals(1, ledger.allReservations().size());
    }

    @Test
    @DisplayName("holds, commits and cancellations batched by four writers keep the books balanced")
    void mixedTrafficConserves() throws Exception {
        Till till = Till.builder(ledger).maxAttempts(200).build();
        till.adjust(key("seed"), sku("widget"), 400);

        try (Writers writers = new Writers(till, 4, 16)) {
            inParallel(80, i -> {
                ReservationId id = rid("r-" + i);
                if (!writers.run(new Command.Reserve(key("hold-" + i), id, List.of(Line.of("widget", 2)), TTL)).ok()) {
                    return;
                }
                writers.run(i % 2 == 0 ? new Command.Commit(key("pay-" + i), id) : new Command.Release(key("cancel-" + i), id));
            });
        }

        long committed = ledger.allReservations().stream()
                .filter(r -> r.state() == ReservationState.COMMITTED)
                .mapToLong(r -> r.lines().get(0).quantity())
                .sum();
        StockItem item = ledger.allStock().get(0);
        assertEquals(400 - committed, item.onHand());
        assertEquals(0, item.reserved());
        assertTrue(committed > 0, "nothing was sold, so nothing was proved");
    }

    @Test
    @DisplayName("four batches that read the same row at once: one is written, the others are refused, decide again, and sell nothing twice")
    void batchesThatReadTogetherConflict() throws Exception {
        Till.builder(ledger).build().adjust(key("seed"), sku("widget"), 10);
        // Every batch's first load waits for the other three, so all four read the row at one version
        // and three of their writes must find it moved. Without this, batches on a quiet laptop can
        // take their turns in order and never meet.
        Meeting meeting = new Meeting(ledger, 4);
        Till till = Till.builder(meeting).maxAttempts(50).build();
        List<Till.BatchResult> results = new java.util.concurrent.CopyOnWriteArrayList<>();

        inParallel(4, writer -> {
            List<Till.Call> batch = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                batch.add(Till.Call.of(new Command.Reserve(
                        key("w" + writer + "-" + i), rid("w" + writer + "-" + i), List.of(Line.of("widget", 1)), TTL)));
            }
            results.add(till.executeAll(batch));
        });

        int conflicts = results.stream().mapToInt(Till.BatchResult::conflicts).sum();
        long reserved = results.stream().flatMap(result -> result.answers().stream())
                .filter(answer -> answer.outcome() instanceof Outcome.Reserved)
                .count();
        assertTrue(conflicts >= 3, "three of the four batches read a version that had moved by their write: " + conflicts);
        assertEquals(10, reserved, "ten units, ten holds, whichever batches they went to");
        assertEquals(10, ledger.allStock().get(0).reserved());
        assertEquals(10, ledger.allReservations().size());
    }

    /**
     * Writers in front of one till, as several ledger tasks would be: a queue of commands, and threads
     * each taking whatever has queued, up to a batch, through {@link Till#executeAll}.
     *
     * <p>They start once as many commands have queued as one batch holds, so that the first batches
     * are full whatever the machine's speed: the callers enqueue and wait, and a writer that started
     * at once could take them one at a time on a quiet laptop and never batch at all.
     */
    private static final class Writers implements AutoCloseable {

        private record Pending(Command command, CompletableFuture<Outcome> answer) {}

        private final BlockingQueue<Pending> queue = new LinkedBlockingQueue<>();
        private final ExecutorService threads;
        private final AtomicInteger largest = new AtomicInteger();
        private volatile boolean closed;

        Writers(Till till, int writers, int maxBatch) {
            threads = Executors.newFixedThreadPool(writers);
            for (int w = 0; w < writers; w++) {
                threads.submit(() -> {
                    long giveUp = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
                    while (queue.size() < maxBatch && System.nanoTime() < giveUp) {
                        java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
                    }
                    List<Pending> batch = new ArrayList<>();
                    while (!closed) {
                        Pending first = queue.poll(50, TimeUnit.MILLISECONDS);
                        if (first == null) {
                            continue;
                        }
                        batch.add(first);
                        queue.drainTo(batch, maxBatch - 1);
                        largest.accumulateAndGet(batch.size(), Math::max);
                        List<Till.Answer> answers =
                                till.executeAll(batch.stream().map(pending -> Till.Call.of(pending.command())).toList()).answers();
                        for (int i = 0; i < batch.size(); i++) {
                            Till.Answer answer = answers.get(i);
                            if (answer.failure() != null) {
                                batch.get(i).answer().completeExceptionally(answer.failure());
                            } else {
                                batch.get(i).answer().complete(answer.outcome());
                            }
                        }
                        batch.clear();
                    }
                    return null;
                });
            }
        }

        Outcome run(Command command) {
            Pending pending = new Pending(command, new CompletableFuture<>());
            queue.add(pending);
            return pending.answer().join();
        }

        int largestBatch() {
            return largest.get();
        }

        @Override
        public void close() {
            closed = true;
            threads.shutdown();
        }
    }

    /** A ledger whose first {@code parties} batch loads wait for each other before any of them returns. */
    private static final class Meeting implements Ledger {
        private final Ledger inner;
        private final CyclicBarrier barrier;
        private final LongAdder loads = new LongAdder();
        private final int parties;

        Meeting(Ledger inner, int parties) {
            this.inner = inner;
            this.parties = parties;
            this.barrier = new CyclicBarrier(parties);
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
            BatchSnapshot snapshot = inner.loadBatch(commands);
            loads.increment();
            if (loads.sum() <= parties) {
                try {
                    barrier.await(30, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new IllegalStateException("the batches never met", e);
                }
            }
            return snapshot;
        }

        @Override
        public boolean applyBatch(BatchDecision decision) {
            return inner.applyBatch(decision);
        }
    }

    /** Runs callers against a starting gun, so that they arrive together rather than in turn. */
    private static void inParallel(int callers, IntConsumer work) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(Math.min(callers, 32))) {
            for (int i = 0; i < callers; i++) {
                int caller = i;
                futures.add(pool.submit(() -> {
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
