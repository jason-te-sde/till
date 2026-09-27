package io.till.store.demo;

import io.till.core.Sku;
import io.till.store.StoreProperties;
import io.till.store.catalogue.Games;
import io.till.store.ledger.LedgerKeys;
import io.till.store.ledger.OperatorLedger;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Stocks every catalogue game on startup — for the demonstration stack, and nothing else.
 *
 * <p>Off unless {@code store.demo.seed-stock} is set, which only the compose file does. A production
 * store does not invent inventory; a demonstration one that opens with every game sold out is a
 * demonstration of nothing.
 *
 * <p>Safe to run on every start, and on every instance at once: each game's adjustment carries the
 * same idempotency key every time, so the ledger replays the first one instead of adding the stock
 * again. Restarting the stack a hundred times leaves exactly the stock it had after the first.
 *
 * <p>Seeded through the ledger rather than written into the store's own tables, so it travels the
 * whole path a real delivery would: ledger, outbox, Kafka, and the projection this service keeps.
 */
@Component
class DemoStock {

    private static final Logger LOG = LoggerFactory.getLogger(DemoStock.class);
    private static final int ATTEMPTS = 10;

    private final StoreProperties.Demo demo;
    private final Games games;
    private final OperatorLedger ledger;

    DemoStock(StoreProperties properties, Games games, OperatorLedger ledger) {
        this.demo = properties.demo();
        this.games = games;
        this.ledger = ledger;
    }

    /**
     * On a virtual thread of its own, so a slow or late ledger delays the stock rather than delaying
     * this service's readiness.
     */
    @EventListener(ApplicationReadyEvent.class)
    void seed() {
        if (!demo.seedStock()) {
            return;
        }
        Thread.ofVirtual().name("demo-stock").start(this::seedWithRetries);
    }

    private void seedWithRetries() {
        List<String> skus = games.skus();
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            try {
                for (String sku : skus) {
                    long units = demo.unitsFor(sku);
                    // Zero means "leave it unstocked": the ledger refuses an adjustment of nothing, and a
                    // game it has never heard of is itself a state worth the demonstration showing.
                    if (units > 0) {
                        ledger.adjust(LedgerKeys.system("demo-stock", sku), Sku.of(sku), units);
                    }
                }
                LOG.info("demonstration stock in place for {} games", skus.size());
                return;
            } catch (RuntimeException e) {
                LOG.warn("seeding demonstration stock failed (attempt {} of {}): {}", attempt, ATTEMPTS, e.getMessage());
                sleep(Duration.ofSeconds(Math.min(2L * attempt, 10)));
            }
        }
        LOG.error("gave up seeding demonstration stock; the store will show every game as sold out");
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
