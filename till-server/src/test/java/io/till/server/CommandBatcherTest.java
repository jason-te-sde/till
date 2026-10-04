package io.till.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.till.core.BatchDecision;
import io.till.core.BatchSnapshot;
import io.till.core.Command;
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
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CommandBatcherTest {

    private final InMemoryLedger memory = new InMemoryLedger();
    private final GatedLedger ledger = new GatedLedger(memory);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final ExecutorService callers = Executors.newCachedThreadPool();
    private CommandBatcher batcher;

    @AfterEach
    void stop() {
        ledger.open();
        if (batcher != null) {
            batcher.close();
        }
        callers.shutdownNow();
    }

    @Test
    @DisplayName("a command on its own is answered as executing it would have been")
    void aLoneCommandIsAnsweredAsExecuteWould() {
        batcher = new CommandBatcher(Till.on(ledger), 64, 16, registry);
        stock("widget", 5);

        Outcome outcome = batcher.submit(reserve("k1", "r1", "widget", 2), null).get();

        assertInstanceOf(Outcome.Reserved.class, outcome);
        assertEquals(3, memory.stock(Sku.of("widget")).orElseThrow().available());
    }

    @Test
    @DisplayName("commands that wait while a batch is written are taken together, in one load")
    void commandsThatWaitTogetherShareOneLoad() throws Exception {
        batcher = new CommandBatcher(Till.on(ledger), 64, 16, registry);
        stock("widget", 100);
        ledger.closeAfter(1);

        Future<Till.Answer> first = callers.submit(() -> batcher.submit(reserve("k0", "r0", "widget", 1), null));
        ledger.awaitHeld();
        List<Future<Till.Answer>> waiting = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            String n = Integer.toString(i);
            waiting.add(callers.submit(() -> batcher.submit(reserve("k" + n, "r" + n, "widget", 1), null)));
        }
        awaitQueued(5);
        ledger.open();

        assertInstanceOf(Outcome.Reserved.class, first.get(5, TimeUnit.SECONDS).get());
        for (Future<Till.Answer> answer : waiting) {
            assertInstanceOf(Outcome.Reserved.class, answer.get(5, TimeUnit.SECONDS).get());
        }
        assertEquals(2, ledger.batchLoads(), "one load for the first command, one for the five that waited");
        assertEquals(6, registry.get("till.batch.size").summary().totalAmount());
    }

    @Test
    @DisplayName("a command arriving at a full queue is refused at once, without waiting")
    void aFullQueueIsRefusedAtOnce() throws Exception {
        batcher = new CommandBatcher(Till.on(ledger), 64, 1, registry);
        stock("widget", 100);
        ledger.closeAfter(1);

        callers.submit(() -> batcher.submit(reserve("k0", "r0", "widget", 1), null));
        ledger.awaitHeld();
        callers.submit(() -> batcher.submit(reserve("k1", "r1", "widget", 1), null));
        awaitQueued(1);

        assertThrows(CommandBatcher.QueueFullException.class,
                () -> batcher.submit(reserve("k2", "r2", "widget", 1), null));
        assertEquals(1.0, registry.get("till.batch.refused").counter().count());
    }

    @Test
    @DisplayName("a closed batcher refuses a command at once instead of queueing it for nobody")
    void aClosedBatcherRefusesAtOnce() {
        batcher = new CommandBatcher(Till.on(ledger), 64, 16, registry);
        batcher.close();

        // Preemptively timed: a closed batcher that queued the command would wait for it forever.
        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(Duration.ofSeconds(2), () ->
                assertThrows(CommandBatcher.QueueFullException.class,
                        () -> batcher.submit(reserve("k1", "r1", "widget", 1), null)));
    }

    @Test
    @DisplayName("a command's failure reaches its own caller, as executing it would have thrown it")
    void aFailureReachesItsCaller() {
        batcher = new CommandBatcher(Till.on(ledger), 64, 16, registry);
        stock("widget", 5);

        Till.Answer answer = batcher.submit(reserve("k1", "r1", "widget", 1), Instant.EPOCH);

        assertThrows(io.till.core.DeadlineExceededException.class, answer::get);
    }

    private void awaitQueued(int count) throws InterruptedException {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (batcher.queued() < count) {
            assertTrue(System.nanoTime() < until, "commands never queued");
            Thread.sleep(5);
        }
    }

    private void stock(String sku, long units) {
        Till.on(memory).adjust(IdempotencyKey.of("stock-" + sku), Sku.of(sku), units);
    }

    private static Command reserve(String key, String id, String sku, long units) {
        return new Command.Reserve(IdempotencyKey.of(key), ReservationId.of(id), List.of(Line.of(sku, units)),
                Duration.ofMinutes(15));
    }

    /** A ledger whose batch loads can be held, to make commands wait behind one being written. */
    private static final class GatedLedger implements Ledger {

        private final InMemoryLedger delegate;
        private final AtomicInteger batchLoads = new AtomicInteger();
        private final CountDownLatch held = new CountDownLatch(1);
        private volatile CountDownLatch gate = new CountDownLatch(0);
        private volatile int holdOnLoad = -1;

        GatedLedger(InMemoryLedger delegate) {
            this.delegate = delegate;
        }

        void closeAfter(int load) {
            gate = new CountDownLatch(1);
            holdOnLoad = load;
        }

        void open() {
            gate.countDown();
        }

        void awaitHeld() throws InterruptedException {
            assertTrue(held.await(5, TimeUnit.SECONDS), "the first batch never reached its load");
        }

        int batchLoads() {
            return batchLoads.get();
        }

        @Override
        public BatchSnapshot loadBatch(List<Command> commands) {
            if (batchLoads.incrementAndGet() == holdOnLoad) {
                held.countDown();
                try {
                    gate.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return delegate.loadBatch(commands);
        }

        @Override
        public boolean applyBatch(BatchDecision decision) {
            return delegate.applyBatch(decision);
        }

        @Override
        public Snapshot load(Command command, Instant now, int reclaimLimit) {
            return delegate.load(command, now, reclaimLimit);
        }

        @Override
        public boolean apply(Decision decision) {
            return delegate.apply(decision);
        }
    }
}
