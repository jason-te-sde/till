package io.till.store.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.micrometer.core.instrument.MeterRegistry;
import io.till.core.Event;
import io.till.core.IdempotencyKey;
import io.till.core.OutboxEntry;
import io.till.core.Sku;
import io.till.kafka.KafkaEventPublisher;
import io.till.kafka.KafkaProducers;
import io.till.kafka.KafkaTopic;
import io.till.store.StoreProperties;
import io.till.store.StoreTest;
import io.till.store.catalogue.Availability;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.producer.Producer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.testcontainers.kafka.KafkaContainer;

/**
 * A store that starts before the ledger has declared its topic.
 *
 * <p>The ledger declares the topic with twelve partitions before it publishes. A consumer that asked
 * for the topic first, and was allowed to, would have had the broker create it with its default of
 * one; the ledger would then grow it, and that consumer would go on reading the one partition it was
 * given until its next metadata refresh — five minutes, by default, during which eleven twelfths of
 * the stream go unread.
 */
class EventConsumerTopicTest extends StoreTest {

    private static final Instant AT = Instant.parse("2026-10-01T00:00:00Z");
    private static final int SKUS = 24;

    /** The JVM image: the native one segfaults on hosts whose container UID has no passwd entry. */
    private static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.1.0");

    @BeforeAll
    static void startBroker() {
        KAFKA.start();
    }

    @AfterAll
    static void stopBroker() {
        KAFKA.stop();
    }

    @Autowired
    Projector projector;

    @Autowired
    MeterRegistry meters;

    @Autowired
    StoreProperties properties;

    @Autowired
    Availability availability;

    @Test
    @DisplayName("a store that starts before the ledger's topic exists does not create it, and reads every partition once it does")
    void readsEveryPartitionOfATopicDeclaredLater() throws Exception {
        String topic = "till.events." + UUID.randomUUID();
        EventConsumer store = new EventConsumer(reading(topic), projector, meters);
        store.start();
        try (Admin admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()));
                Producer<String, String> producer =
                        KafkaProducers.create(KAFKA.getBootstrapServers(), Duration.ofSeconds(30), Map.of())) {
            // Long enough for the store to have asked for the topic, more than once.
            Thread.sleep(3_000);
            assertFalse(
                    admin.listTopics().names().get().contains(topic),
                    "the store created the ledger's topic, which gets the broker's default of one partition");

            new KafkaTopic(admin, topic, 12, (short) 1, Duration.ofSeconds(30)).ensure();
            // An adjustment is keyed by its SKU, so twenty-four of them land all over twelve partitions.
            List<OutboxEntry> adjustments = new ArrayList<>();
            for (int i = 0; i < SKUS; i++) {
                adjustments.add(new OutboxEntry(
                        i + 1, new Event.StockAdjusted(IdempotencyKey.of("d" + i), Sku.of("sku-" + i), 10 + i, 10 + i, 0, AT), AT));
            }
            new KafkaEventPublisher(producer, topic, Duration.ofSeconds(30)).publish(adjustments);

            Instant deadline = Instant.now().plusSeconds(20);
            while (projected() < SKUS && Instant.now().isBefore(deadline)) {
                Thread.sleep(100);
            }
        } finally {
            store.stop();
        }

        for (int i = 0; i < SKUS; i++) {
            long onHand = availability.of("sku-" + i).map(Availability.Level::onHand).orElse(-1L);
            assertEquals(10L + i, onHand, "sku-" + i);
        }
    }

    private int projected() {
        int found = 0;
        for (int i = 0; i < SKUS; i++) {
            if (availability.of("sku-" + i).isPresent()) {
                found++;
            }
        }
        return found;
    }

    private StoreProperties reading(String topic) {
        return new StoreProperties(
                properties.till(),
                new StoreProperties.Kafka(KAFKA.getBootstrapServers(), topic, "store-" + UUID.randomUUID(), Duration.ofMillis(200)),
                properties.auth(), properties.checkout(), properties.demo(), properties.catalogue(), properties.sales());
    }
}
