package io.till.server;

import io.micrometer.core.instrument.MeterRegistry;
import io.till.core.EventPublisher;
import io.till.core.OutboxEntry;
import io.till.jdbc.JdbcLedger;
import java.time.Clock;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drains the outbox.
 *
 * <p>Reads the oldest unpublished entries, hands them to an {@link EventPublisher}, and marks them
 * afterwards. Marking afterwards is the whole of the delivery guarantee: a crash between the send and
 * the mark repeats the send, so delivery is at least once and never at most once. Doing it the other
 * way round would lose an event every time this process died at the wrong moment, and nothing
 * downstream would ever know which one.
 *
 * <p>Several instances may run this at once. Two of them can deliver the same batch — the duplicate
 * is the consumer's to drop, which it can, because every event carries a stable deduplication key.
 * Coordinating them instead would buy exactly-once at the cost of a lock on the hot path, and the
 * consumer needs to be idempotent anyway.
 *
 * <p>The backlog gauge is deliberately <b>not</b> here. It reports on this component, so keeping it
 * here made it accurate only while this component was working — see {@link TillMetrics}.
 */
@Component
@ConditionalOnProperty(prefix = "till.outbox", name = "enabled", havingValue = "true", matchIfMissing = true)
class OutboxPublisher {

    private static final Logger LOG = LoggerFactory.getLogger(OutboxPublisher.class);

    private final JdbcLedger ledger;
    private final EventPublisher publisher;
    private final TillProperties properties;
    private final Clock clock;
    private final MeterRegistry registry;

    /**
     * @param ledger the outbox to drain
     * @param publishers any {@link EventPublisher} bean the application defined; a log-line
     *     publisher is used when there is none. Resolved through a provider rather than declared as
     *     an optional dependency so that the fallback does not depend on the order beans happen to
     *     be scanned in
     * @param properties the batch size and interval
     * @param clock stamps the delivery instant, so that retention compares two readings of one clock
     * @param registry where the counters go
     */
    OutboxPublisher(
            JdbcLedger ledger,
            ObjectProvider<EventPublisher> publishers,
            TillProperties properties,
            Clock clock,
            MeterRegistry registry) {
        this.ledger = ledger;
        this.publisher = publishers.getIfAvailable(LoggingEventPublisher::new);
        this.properties = properties;
        this.clock = clock;
        this.registry = registry;
    }

    /**
     * Publishes one batch.
     *
     * <p>Scheduled with a fixed delay rather than a fixed rate: at a fixed rate a publisher that
     * falls behind is asked to start another run before the last one finished, which turns a slow
     * broker into a growing pile of concurrent runs all fighting over the same rows.
     */
    @Scheduled(fixedDelayString = "${till.outbox.interval:1s}")
    void drain() {
        List<OutboxEntry> batch = ledger.unpublished(properties.outbox().batch());
        if (batch.isEmpty()) {
            return;
        }
        try {
            publisher.publish(batch);
        } catch (RuntimeException e) {
            // Nothing is marked, so the same batch is offered again next time. That is the correct
            // response to a broker that is down, and it is why delivery is at least once.
            LOG.warn("publishing {} events failed; they will be offered again", batch.size(), e);
            registry.counter("till.outbox.failures").increment();
            return;
        }
        ledger.markPublished(batch.stream().map(OutboxEntry::sequence).toList(), clock.instant());
        registry.counter("till.outbox.published").increment(batch.size());
    }
}
