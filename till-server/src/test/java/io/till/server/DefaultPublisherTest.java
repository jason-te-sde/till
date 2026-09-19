package io.till.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.till.client.TillClient;
import io.till.core.EventPublisher;
import io.till.core.IdempotencyKey;
import io.till.core.Sku;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * The service starts and publishes with no {@link EventPublisher} bean defined.
 *
 * <p>Which is how it is deployed, and which the rest of the integration suite cannot check: those
 * tests supply a publisher so they can read what it was handed, and in doing so they satisfy the
 * very dependency whose absence once stopped the service from starting at all. The context here is
 * the production wiring, and the assertion is that there is one.
 */
// Suites that share the one database run one at a time; see the note in the other API tests.
@ResourceLock("till-database")
@TestPropertySource(properties = {"till.outbox.enabled=true", "till.outbox.interval=1h"})
class DefaultPublisherTest extends ApiTestBase {

    @Autowired
    OutboxPublisher publisher;

    @Autowired
    org.springframework.context.ApplicationContext context;

    @Test
    @DisplayName("an unset broker list leaves Kafka out entirely, rather than half-configured")
    void kafkaIsOffWhenUnconfigured() {
        // application.yml carries `bootstrap-servers: ${TILL_KAFKA_BROKERS:}`, which is *present*
        // and empty on every deployment that has not opted in. @ConditionalOnProperty matched on
        // presence, built a producer with no brokers, and Kafka's config validation then failed the
        // bean — so the service refused to start by default. This is that regression.
        assertTrue(
                context.getBeansOfType(org.apache.kafka.clients.producer.Producer.class).isEmpty(),
                "no broker configured, so there should be no producer");
        assertTrue(
                context.getBeansOfType(EventPublisher.class).isEmpty(),
                "and no publisher bean, so OutboxPublisher falls back to log lines");
    }

    @Test
    @DisplayName("the default publisher exists, and draining the outbox with it works")
    void theDefaultPublisherWorks() {
        TillClient till = admin();
        till.adjust(IdempotencyKey.of("d1"), Sku.of("widget"), 10);
        assertEquals(1, ledger.backlog());

        publisher.drain();

        assertEquals(0, ledger.backlog(), "the default publisher delivered it and marked it");
        assertTrue(ledger.allEvents().size() == 1);
    }
}
