package io.till.server;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.till.core.Command;
import io.till.core.Till;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Takes the commands that are waiting at the same time and runs them together, through
 * {@link Till#executeAll} (ADR 16).
 *
 * <p>One worker takes whatever has queued, up to {@code maxBatch}, and nothing waits on purpose: a
 * command arriving at an idle ledger is a batch of one and runs at once, and batches grow only while
 * the one before them is being written, which is exactly when there is something to share. Each caller
 * still gets its own answer, the one running its command alone would have given.
 *
 * <p>The queue is bounded. A command that arrives to a full one is refused at once
 * ({@link QueueFullException}, answered 503 {@code OVERLOADED}) rather than left to wait out its
 * caller's deadline behind it.
 */
final class CommandBatcher implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(CommandBatcher.class);

    private final Till till;
    private final int maxBatch;
    private final BlockingQueue<Pending> queue;
    private final DistributionSummary sizes;
    private final Counter conflicts;
    private final Counter refused;
    private final Thread worker;
    private volatile boolean running = true;

    CommandBatcher(Till till, int maxBatch, int capacity, MeterRegistry registry) {
        if (maxBatch < 1 || capacity < 1) {
            throw new IllegalArgumentException("a batch and the queue each need room for one command");
        }
        this.till = till;
        this.maxBatch = maxBatch;
        this.queue = new ArrayBlockingQueue<>(capacity);
        this.sizes = DistributionSummary.builder("till.batch.size")
                .description("Commands decided together in one load and one write")
                .register(registry);
        this.conflicts = Counter.builder("till.batch.conflicts")
                .description("Batch writes refused because a row had moved, each decided again")
                .register(registry);
        this.refused = Counter.builder("till.batch.refused")
                .description("Commands refused because the queue in front of the ledger was full")
                .register(registry);
        this.worker = new Thread(this::work, "till-batcher");
        this.worker.setDaemon(true);
        this.worker.start();
    }

    /**
     * Queues a command and waits for its answer.
     *
     * @param command what to do
     * @param deadline when its caller stops waiting, or null for no deadline
     * @return the answer running the command alone would have given
     * @throws QueueFullException if the queue is full; nothing was done
     */
    Till.Answer submit(Command command, Instant deadline) {
        Pending pending = new Pending(new Till.Call(command, deadline), new CompletableFuture<>());
        // A closed batcher has no worker, so a command queued now would wait for nobody, forever.
        // Checked before queueing and again after: a close between the two either drained this
        // command, and answered it with a refusal, or left it to be taken back here.
        if (!running || !queue.offer(pending) || (!running && queue.remove(pending))) {
            refused.increment();
            throw new QueueFullException();
        }
        return pending.answer().join();
    }

    /** How many commands are waiting, not counting a batch being run. */
    int queued() {
        return queue.size();
    }

    private void work() {
        List<Pending> batch = new ArrayList<>(maxBatch);
        while (running) {
            try {
                Pending first = queue.poll(100, TimeUnit.MILLISECONDS);
                if (first == null) {
                    continue;
                }
                batch.add(first);
                queue.drainTo(batch, maxBatch - 1);
                run(batch);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } finally {
                batch.clear();
            }
        }
    }

    private void run(List<Pending> batch) {
        List<Till.Call> calls = new ArrayList<>(batch.size());
        for (Pending pending : batch) {
            calls.add(pending.call());
        }
        sizes.record(batch.size());
        try {
            Till.BatchResult result = till.executeAll(calls);
            conflicts.increment(result.conflicts());
            for (int i = 0; i < batch.size(); i++) {
                batch.get(i).answer().complete(result.answers().get(i));
            }
        } catch (RuntimeException e) {
            // executeAll answers every command's failure itself; anything that escapes it is a fault
            // in the batch machinery, and every caller of the batch hears about it.
            LOG.error("a batch of {} commands failed", batch.size(), e);
            for (Pending pending : batch) {
                pending.answer().complete(Till.Answer.failed(e));
            }
        }
    }

    /** Stops taking batches; commands still queued are answered with a refusal. */
    @Override
    public void close() {
        running = false;
        worker.interrupt();
        Pending pending;
        while ((pending = queue.poll()) != null) {
            pending.answer().complete(Till.Answer.failed(new QueueFullException()));
        }
    }

    private record Pending(Till.Call call, CompletableFuture<Till.Answer> answer) {}

    /** The queue in front of the ledger is full: overload, answered 503 {@code OVERLOADED}. */
    static final class QueueFullException extends RuntimeException {
        QueueFullException() {
            super("the queue in front of the ledger is full");
        }
    }
}
