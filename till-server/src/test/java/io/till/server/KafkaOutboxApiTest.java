package io.till.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.till.client.TillClient;
import io.till.core.IdempotencyKey;
import io.till.core.Line;
import io.till.core.Outcome;
import io.till.core.Sku;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.kafka.KafkaContainer;

/**
 * The whole path, with nothing faked: a hold taken over HTTP against a real PostgreSQL, drained by
 * the real scheduled publisher, arriving on a real broker.
 *
 * <p>The unit suite in {@code till-kafka} proves the publisher speaks Kafka correctly. What only this
 * can prove is that the <b>wiring</b> is right — that setting one property actually replaces the
 * log-line publisher, that the scheduler runs, and that an event committed in a database transaction
 * comes out the other end intact. Every bug this project has had in that layer was a wiring bug.
 */
@ResourceLock("till-database")
@TestPropertySource(
        properties = {
            // The publisher runs for real here, unlike every other API suite.
            "till.outbox.enabled=true",
            "till.outbox.interval=200ms",
            "till.retention.enabled=false"
        })
class KafkaOutboxApiTest extends ApiTestBase {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final String TOPIC = "till-events-e2e";

    private static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka-native:4.1.0");

    static {
        KAFKA.start();
    }

    @AfterAll
    static void stopBroker() {
        KAFKA.stop();
    }

    /**
     * Setting this one property is the entire switch from log lines to Kafka, which is the thing
     * being asserted as much as anything below it.
     *
     * @param registry Spring's late-bound property source
     */
    @DynamicPropertySource
    static void kafka(DynamicPropertyRegistry registry) {
        registry.add("till.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("till.kafka.topic", () -> TOPIC);
    }

    @Test
    @DisplayName("a hold taken over HTTP turns up on the broker, without anyone publishing it by hand")
    void theOutboxReachesKafka() {
        TillClient till = admin();
        till.adjust(IdempotencyKey.of("d1"), Sku.of("halo"), 10);
        Outcome.Reserved held = till.reserve(IdempotencyKey.of("c1"), List.of(Line.of("halo", 3)), null);
        till.commit(IdempotencyKey.of("pay-1"), held.id());

        // Three events, delivered by the scheduler on its own. No call to publish() anywhere in this
        // test: if the wiring is wrong this times out with an empty topic.
        List<ConsumerRecord<String, String>> got = drain(3);

        assertEquals(
                List.of("adjusted:d1", "reserved:" + held.id().value(), "committed:" + held.id().value()),
                got.stream().map(r -> header(r, "till-dedupe-key")).toList());
        assertEquals(
                held.id().value(),
                got.get(1).key(),
                "keyed by the reservation, so its lifecycle stays ordered on one partition");
        assertEquals(got.get(1).partition(), got.get(2).partition(), "commit cannot overtake reserve");
        assertTrue(got.get(2).value().contains("halo"), got.get(2).value());

        // And the backlog is drained, which is the other half of the wiring: the publisher marked
        // them published rather than delivering the same three forever.
        Instant deadline = Instant.now().plusSeconds(10);
        while (ledger.backlog() > 0 && Instant.now().isBefore(deadline)) {
            Thread.onSpinWait();
        }
        assertEquals(0, ledger.backlog(), "the publisher marked what it delivered");
    }

    private List<ConsumerRecord<String, String>> drain(int expected) {
        Properties config = new Properties();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        config.put(ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID());
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());

        List<ConsumerRecord<String, String>> collected = new ArrayList<>();
        Instant deadline = Instant.now().plus(TIMEOUT);
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(config)) {
            consumer.subscribe(List.of(TOPIC));
            while (collected.size() < expected && Instant.now().isBefore(deadline)) {
                consumer.poll(Duration.ofMillis(500)).forEach(collected::add);
            }
        }
        assertEquals(expected, collected.size(), "nothing arrived on " + TOPIC);
        // Records land on several partitions, so arrival order is not publish order.
        collected.sort((a, b) -> Long.compare(sequence(a), sequence(b)));
        return collected;
    }

    private static long sequence(ConsumerRecord<String, String> record) {
        return Long.parseLong(header(record, "till-sequence"));
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }
}
