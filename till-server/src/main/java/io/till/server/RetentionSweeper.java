package io.till.server;

import io.micrometer.core.instrument.MeterRegistry;
import io.till.jdbc.JdbcLedger;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.function.BiFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Deletes history nobody needs any more.
 *
 * <p>This exists because the alternative was a paragraph in the operations guide telling somebody to
 * write a cron job. That works until the person who read it changes team, and then a disk fills up
 * at three in the morning over rows that stopped mattering months ago.
 *
 * <p>Deleting in <b>bounded batches, repeatedly</b>, rather than one statement per table. A single
 * delete removing a month of rows holds a lock long enough for everything else to notice; a hundred
 * small ones do not, and an interrupted run has still made progress. Each table is worked until a
 * batch comes back short or the run's budget is used up, and whatever is left waits for the next
 * interval.
 *
 * <p>The cutoffs are deliberately different, because what it costs to be wrong about them is
 * different:
 *
 * <ul>
 *   <li><b>Idempotency records</b> — the dangerous one. Deleting one means a caller retrying that
 *       command executes it <b>again</b>. The default is seven days, which is far longer than any
 *       sensible client retry window, and it is the setting to raise rather than lower. For
 *       <i>adjustments</i> the effective window is the larger of this and the outbox window,
 *       because an adjustment's event is named after its key and the ledger will not forget a key
 *       whose event is still queued. Erring long, in the safe direction, by construction.
 *   <li><b>Published outbox rows</b> — history for replay. Unpublished rows are never touched.
 *   <li><b>Finished reservations</b> — <b>off by default</b>. They are the record of what was held
 *       and by whom, and that is the first thing anybody asks for when stock does not add up.
 * </ul>
 *
 * <p>Safe on every instance at once: the deletes are idempotent and two of them racing produce one
 * deletion and one that finds nothing.
 */
@Component
@ConditionalOnProperty(prefix = "till.retention", name = "enabled", havingValue = "true", matchIfMissing = true)
class RetentionSweeper {

    private static final Logger LOG = LoggerFactory.getLogger(RetentionSweeper.class);

    private final JdbcLedger ledger;
    private final TillProperties properties;
    private final Clock clock;
    private final MeterRegistry registry;

    RetentionSweeper(JdbcLedger ledger, TillProperties properties, Clock clock, MeterRegistry registry) {
        this.ledger = ledger;
        this.properties = properties;
        this.clock = clock;
        this.registry = registry;
    }

    /** Runs one pass over the three tables. */
    @Scheduled(fixedDelayString = "${till.retention.interval:1h}", initialDelayString = "${till.retention.interval:1h}")
    void prune() {
        TillProperties.RetentionPolicy policy = properties.retention();
        try {
            // Outbox first, and not by accident. An adjustment's event is named after its
            // idempotency key, so the ledger refuses to forget a key while that event is still
            // queued. Sweeping the outbox first means one pass can do both once both windows have
            // elapsed; the other order would always need a second pass.
            sweep("outbox", policy.outbox(), ledger::pruneOutbox, policy.batch());
            sweep("idempotency", policy.idempotency(), ledger::forgetIdempotency, policy.batch());
            sweep("reservations", policy.reservations(), ledger::pruneReservations, policy.batch());
        } catch (RuntimeException e) {
            // The scheduler cancels a task that throws, which would stop retention for the lifetime
            // of the process and be noticed only by a disk alert weeks later.
            LOG.error("a retention pass failed", e);
            registry.counter("till.retention.failures").increment();
        }
    }

    private void sweep(String what, Duration keepFor, BiFunction<Instant, Integer, Integer> delete, int batch) {
        if (keepFor.isZero() || keepFor.isNegative()) {
            return;
        }
        Instant before = clock.instant().minus(keepFor);
        int total = 0;
        // A budget rather than "until it is empty": a first run against years of history should take
        // a bounded amount of time and come back for the rest, not hold a connection for an hour.
        for (int pass = 0; pass < properties.retention().passes(); pass++) {
            int deleted = delete.apply(before, batch);
            total += deleted;
            if (deleted < batch) {
                break;
            }
        }
        if (total > 0) {
            registry.counter("till.retention.deleted", "table", what).increment(total);
            LOG.info("deleted {} {} older than {}", total, what, before);
        }
    }
}
