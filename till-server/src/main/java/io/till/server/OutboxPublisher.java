package io.till.server;

import io.micrometer.core.instrument.MeterRegistry;
import io.till.core.EventPublisher;
import io.till.jdbc.JdbcLedger;
import java.time.Clock;
import java.util.OptionalInt;
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
 * <p><b>Until it has caught up.</b> A run publishes batch after batch while each comes back full,
 * up to {@code till.outbox.passes} of them, and waits the interval only once it has caught up. One
 * batch a run was a ceiling of one batch an interval, and a busy checkout writes more than that.
 *
 * <p><b>One instance at a time.</b> Several may run this, and each round is taken under the outbox's
 * publishing claim ({@link io.till.core.Outbox#publishNext}): the instance that holds it publishes, and
 * the others sit the round out. They used not to — two instances delivered the same batches, and the
 * consumers dropped the copies — which was cheap at one batch a second and doubles everything the
 * consumers do once a run drains the backlog. One at a time also keeps the entries in sequence order
 * on their way out, and the next instance takes over the moment one stops holding the claim.
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
     *
     * <p>And with an initial delay of one interval, rather than firing the moment the bean exists.
     * A publisher that runs during context startup is doing work before the application has said it
     * is ready, which in production is merely impolite and in the test suite is a correctness
     * problem: test classes run in parallel, {@code @ResourceLock} guards test <i>methods</i>, and
     * Spring builds a context in {@code beforeAll} — outside the lock. So a second suite's context
     * coming up mid-test drained the first suite's outbox rows to log lines and marked them
     * published, and the events never reached the broker the first suite was watching.
     * {@link RetentionSweeper} already had this for the same reason.
     */
    @Scheduled(fixedDelayString = "${till.outbox.interval:1s}", initialDelayString = "${till.outbox.interval:1s}")
    void drain() {
        TillProperties.Outbox settings = properties.outbox();
        for (int pass = 0; pass < settings.passes(); pass++) {
            OptionalInt published;
            try {
                published = ledger.publishNext(settings.batch(), clock.instant(), publisher::publish);
            } catch (RuntimeException e) {
                // Nothing is marked, so the same batch is offered again next time. That is the correct
                // response to a broker that is down, and it is why delivery is at least once.
                LOG.warn("publishing the outbox failed; the batch will be offered again", e);
                registry.counter("till.outbox.failures").increment();
                return;
            }
            if (published.isEmpty()) {
                // Another instance is publishing; it will drain what this one would have.
                registry.counter("till.outbox.standby").increment();
                return;
            }
            registry.counter("till.outbox.published").increment(published.getAsInt());
            if (published.getAsInt() < settings.batch()) {
                return;
            }
        }
    }
}
