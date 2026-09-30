package io.till.server;

import io.micrometer.core.instrument.MeterRegistry;
import io.till.core.ConflictException;
import io.till.core.Till;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Writes off holds that have run out of time.
 *
 * <p>Not required for correctness, and it is worth being precise about why: a command that would be
 * refused for want of stock writes off the expired holds on its SKUs first, and is decided again.
 * Turning this off makes stock come back later, never never.
 *
 * <p>What it buys is everything else. Commands with stock to spare no longer write expired holds off
 * as they pass — the load tests found them all racing to write off the same ones — so this is what
 * returns abandoned checkouts' stock to {@code available}, keeps a SKU nobody is asking for honest on
 * a dashboard, and keeps the backlog small enough that a command which does need it pays little.
 *
 * <p>Safe to run on every instance. Two sweepers reaching the same hold produce one write and one
 * conflict, and the conflict is answered by doing nothing, because the hold is now in the state the
 * loser wanted it in.
 */
@Component
@ConditionalOnProperty(prefix = "till.sweeper", name = "enabled", havingValue = "true", matchIfMissing = true)
class ExpirySweeper {

    private static final Logger LOG = LoggerFactory.getLogger(ExpirySweeper.class);

    private final Till till;
    private final TillProperties properties;
    private final MeterRegistry registry;

    ExpirySweeper(Till till, TillProperties properties, MeterRegistry registry) {
        this.till = till;
        this.properties = properties;
        this.registry = registry;
    }

    /**
     * Runs one sweep: batches while they come back full, up to the configured passes, so that a
     * burst of abandoned checkouts drains in a few runs. A budget rather than "until none are left":
     * a sweep that could run for as long as the backlog lasts would hold the database for as long.
     */
    @Scheduled(fixedDelayString = "${till.sweeper.interval:5s}")
    void sweep() {
        try {
            int batch = properties.sweeper().batch();
            int total = 0;
            for (int pass = 0; pass < properties.sweeper().passes(); pass++) {
                int expired = till.sweep(batch);
                total += expired;
                if (expired < batch) {
                    break;
                }
            }
            if (total > 0) {
                registry.counter("till.sweeper.expired").increment(total);
                LOG.debug("wrote off {} expired holds", total);
            }
        } catch (ConflictException e) {
            // Another instance is sweeping the same holds. There is nothing to do about that and
            // nothing to fix: they will be gone by the next run either way.
            registry.counter("till.sweeper.contended").increment();
        } catch (RuntimeException e) {
            // The scheduler cancels a task that throws, which would silently stop sweeping for the
            // lifetime of the process. Log it and come back in a few seconds instead.
            LOG.error("a sweep failed", e);
            registry.counter("till.sweeper.failures").increment();
        }
    }
}
