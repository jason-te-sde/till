package io.till.server;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.till.core.Command;
import io.till.core.Till;
import java.time.Duration;
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
 *
 * <p>Every command waits behind the batch in front of it, so a batch that does not finish holds up
 * all of them. A watchdog looks at the worker several times a {@code stallAfter}: a batch running
 * for longer than that is reported once, as {@code till.batch.stalls} and a warning carrying where
 * the worker is; a look that comes much later than it was due means the whole process was held up,
 * not only its worker, and is reported as {@code till.batch.pauses}.
 */
final class CommandBatcher implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(CommandBatcher.class);

    private final Till till;
    private final int maxBatch;
    private final BlockingQueue<Pending> queue;
    private final MeterRegistry registry;
    private final long stallNanos;
    private final long lookNanos;
    private final Thread worker;
    private final Thread watchdog;
    private volatile boolean running = true;
    private volatile InFlight inFlight;

    // Guarded by this: the watchdog's own state, read and written only in watch().
    private InFlight reported;
    private boolean looked;
    private long lastLook;

    CommandBatcher(Till till, int maxBatch, int capacity, Duration stallAfter, MeterRegistry registry) {
        if (maxBatch < 1 || capacity < 1) {
            throw new IllegalArgumentException("a batch and the queue each need room for one command");
        }
        if (stallAfter.isZero() || stallAfter.isNegative()) {
            throw new IllegalArgumentException("a stall is longer than no time at all");
        }
        this.till = till;
        this.maxBatch = maxBatch;
        this.queue = new ArrayBlockingQueue<>(capacity);
        this.registry = registry;
        this.stallNanos = stallAfter.toNanos();
        this.lookNanos = Math.max(stallNanos / 4, TimeUnit.MILLISECONDS.toNanos(10));
        this.worker = new Thread(this::work, "till-batcher");
        this.worker.setDaemon(true);
        this.watchdog = new Thread(this::lookout, "till-batcher-watchdog");
        this.watchdog.setDaemon(true);
        this.worker.start();
        this.watchdog.start();
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
            refused().increment();
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
        sizes().record(batch.size());
        InFlight batchInFlight = new InFlight(batch.size(), System.nanoTime());
        inFlight = batchInFlight;
        try {
            Till.BatchResult result = till.executeAll(calls);
            conflicts().increment(result.conflicts());
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
        } finally {
            inFlight = null;
            if (wasReported(batchInFlight)) {
                LOG.warn("the batch of {} commands that stalled took {} ms in all", batchInFlight.size(),
                        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - batchInFlight.since()));
            }
        }
    }

    /**
     * One look at the worker, at {@code now} on {@link System#nanoTime}'s clock: reports a batch that
     * has been running for longer than {@code stallAfter}, once, and a look that comes far later than it
     * was due.
     *
     * @param now when this look is
     */
    synchronized void watch(long now) {
        if (looked && now - lastLook > lookNanos + stallNanos) {
            pauses().increment();
            LOG.warn("the batcher's watchdog looked {} ms after it was due: the whole process was held up, not only its worker",
                    TimeUnit.NANOSECONDS.toMillis(now - lastLook - lookNanos));
        }
        looked = true;
        lastLook = now;
        InFlight batchInFlight = inFlight;
        if (batchInFlight != null && batchInFlight != reported && now - batchInFlight.since() > stallNanos) {
            reported = batchInFlight;
            stalls().increment();
            LOG.warn("a batch of {} commands has been running for {} ms, and every command waits behind it",
                    batchInFlight.size(), TimeUnit.NANOSECONDS.toMillis(now - batchInFlight.since()),
                    new WorkerStack(worker.getStackTrace()));
        }
    }

    private synchronized boolean wasReported(InFlight batchInFlight) {
        return reported == batchInFlight;
    }

    private void lookout() {
        while (running) {
            try {
                TimeUnit.NANOSECONDS.sleep(lookNanos);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            watch(System.nanoTime());
        }
    }

    /** Stops taking batches; commands still queued are answered with a refusal. */
    @Override
    public void close() {
        running = false;
        worker.interrupt();
        watchdog.interrupt();
        Pending pending;
        while ((pending = queue.poll()) != null) {
            pending.answer().complete(Till.Answer.failed(new QueueFullException()));
        }
    }

    // Looked up at each use rather than held, as Commands does: a meter held from construction is
    // dropped by a registry that is cleared, and goes on counting where nothing reads it.
    private DistributionSummary sizes() {
        return DistributionSummary.builder("till.batch.size")
                .description("Commands decided together in one load and one write")
                .register(registry);
    }

    private Counter conflicts() {
        return Counter.builder("till.batch.conflicts")
                .description("Batch writes refused because a row had moved, each decided again")
                .register(registry);
    }

    private Counter stalls() {
        return Counter.builder("till.batch.stalls")
                .description("Batches that ran for longer than till.batch.stall-after, holding up every command behind them")
                .register(registry);
    }

    private Counter pauses() {
        return Counter.builder("till.batch.pauses")
                .description("Looks of the batcher's watchdog that came far later than due: the whole process was held up")
                .register(registry);
    }

    private Counter refused() {
        return Counter.builder("till.batch.refused")
                .description("Commands refused because the queue in front of the ledger was full")
                .register(registry);
    }

    private record Pending(Till.Call call, CompletableFuture<Till.Answer> answer) {}

    /** The batch the worker is running: how many commands, and since when on the nanosecond clock. */
    private record InFlight(int size, long since) {}

    /** The worker's stack, carried as a throwable so that the log prints it as one. */
    private static final class WorkerStack extends Throwable {
        WorkerStack(StackTraceElement[] frames) {
            super("where the worker is", null, false, true);
            setStackTrace(frames);
        }
    }

    /** The queue in front of the ledger is full: overload, answered 503 {@code OVERLOADED}. */
    static final class QueueFullException extends RuntimeException {
        QueueFullException() {
            super("the queue in front of the ledger is full");
        }
    }
}
