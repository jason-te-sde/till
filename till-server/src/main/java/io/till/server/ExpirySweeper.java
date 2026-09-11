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
 * <p>Not required for correctness, and it is worth being precise about why: a hold past its deadline
 * already counts as expired everywhere a decision is made, and any command that needs the stock it
 * is sitting on reclaims it on the way past. Turning this off makes stock come back later, never
 * never.
 *
 * <p>What it buys is that a SKU nobody is asking for still shows the right {@code available} on a
 * dashboard, and that a burst of abandoned checkouts does not leave the next customer's request
 * paying to reclaim all of them at once.
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

    /** Runs one sweep. */
    @Scheduled(fixedDelayString = "${till.sweeper.interval:5s}")
    void sweep() {
        try {
            int expired = till.sweep(properties.sweeper().batch());
            if (expired > 0) {
                registry.counter("till.sweeper.expired").increment(expired);
                LOG.debug("wrote off {} expired holds", expired);
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
