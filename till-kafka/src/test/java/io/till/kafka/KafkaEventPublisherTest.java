package io.till.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.till.core.Codec;
import io.till.core.Event;
import io.till.core.IdempotencyKey;
import io.till.core.Line;
import io.till.core.OutboxEntry;
import io.till.core.ReservationId;
import io.till.core.Sku;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

/**
 * Against a real broker in a container, because the properties worth asserting here do not exist in
 * a mock.
 *
 * <p>Whether a record is on the partition its key implies, whether the headers survive the wire,
 * whether a send to a broker that is not there throws instead of quietly dropping the batch — a
 * fake producer answers all three the way its author expected rather than the way Kafka does.
 */
@Testcontainers
class KafkaEventPublisherTest {

    private static final Instant T0 = Instant.parse("2026-09-18T12:00:00Z");
    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    private static KafkaContainer kafka;

    @BeforeAll
    static void startBroker() {
        kafka = new KafkaContainer("apache/kafka-native:4.1.0");
        kafka.start();
    }

    @AfterAll
    static void stopBroker() {
        if (kafka != null) {
            kafka.stop();
        }
    }

    @Test
    @DisplayName("every event in the batch arrives, carrying what a consumer needs to act on it")
    void publishesTheBatch() {
        String topic = topic();
        List<OutboxEntry> batch =
                List.of(
                        entry(1, new Event.StockAdjusted(key("d1"), Sku.of("halo"), 10, 10, 0, T0)),
                        entry(2, reserved("r1", "halo", 2)),
                        entry(3, new Event.StockCommitted(ReservationId.of("r1"), List.of(Line.of("halo", 2)), T0)));

        publish(topic, batch);
        List<ConsumerRecord<String, String>> got = drain(topic, 3);

        assertEquals(3, got.size());
        // The payload is byte-identical to what the outbox committed. Re-encoding it here would let
        // the published form drift from the stored one, and the stored one is the audit record.
        assertEquals(
                batch.stream().map(e -> Codec.encodeEvent(e.event())).toList(),
                got.stream().map(ConsumerRecord::value).toList());
        assertEquals(
                List.of("adjusted:d1", "reserved:r1", "committed:r1"),
                got.stream().map(r -> header(r, KafkaEventPublisher.HEADER_DEDUPE_KEY)).toList(),
                "the key a consumer deduplicates on");
        assertEquals(
                List.of("1", "2", "3"),
                got.stream().map(r -> header(r, KafkaEventPublisher.HEADER_SEQUENCE)).toList(),
                "the outbox sequence, for a consumer that needs to detect a gap or a reorder");
        assertEquals(
                List.of("StockAdjusted", "StockReserved", "StockCommitted"),
                got.stream().map(r -> header(r, KafkaEventPublisher.HEADER_TYPE)).toList(),
                "so a consumer can route without parsing the body");
        assertEquals(T0.toString(), header(got.get(0), KafkaEventPublisher.HEADER_OCCURRED_AT));
    }

    @Test
    @DisplayName("one reservation's events share a key, so its lifecycle cannot arrive out of order")
    void keysByEntity() {
        String topic = topic();
        publish(
                topic,
                List.of(
                        entry(1, reserved("r-77", "halo", 1)),
                        entry(2, new Event.StockCommitted(ReservationId.of("r-77"), List.of(Line.of("halo", 1)), T0)),
                        entry(3, new Event.StockAdjusted(key("d9"), Sku.of("halo"), 5, 15, 0, T0))));

        List<ConsumerRecord<String, String>> got = drain(topic, 3);

        // Kafka orders within a partition and the partition follows the key. Same key means same
        // partition means ordered — which is the entire mechanism, and the reason a null key here
        // would be a silent correctness bug rather than a style preference.
        assertEquals("r-77", got.get(0).key());
        assertEquals("r-77", got.get(1).key());
        assertEquals(got.get(0).partition(), got.get(1).partition(), "so commit cannot overtake reserve");
        // An adjustment has no reservation to belong to, so it is keyed by the SKU it moves.
        assertEquals("halo", got.get(2).key());
    }

    @Test
    @DisplayName("a broker that is not there fails fast, so the publisher is not held on a dead socket")
    void failureThrows() {
        Duration budget = Duration.ofSeconds(2);
        // Port 1 is never a Kafka broker.
        try (Producer<String, String> doomed = KafkaProducers.create("127.0.0.1:1", budget, Map.of())) {
            KafkaEventPublisher publisher = new KafkaEventPublisher(doomed, "anything", Duration.ofSeconds(5));
            Instant start = Instant.now();

            KafkaEventPublisher.PublishException thrown =
                    assertThrows(
                            KafkaEventPublisher.PublishException.class,
                            () -> publisher.publish(List.of(entry(1, reserved("r1", "halo", 1)))));
            Duration took = Duration.between(start, Instant.now());

            // Silence here would be the worst possible outcome: OutboxPublisher would mark the batch
            // published and the events would be gone with nothing in the logs.
            assertTrue(thrown.getMessage().contains("reserved:r1"), thrown.getMessage());
            assertTrue(thrown.getMessage().contains("retried"), thrown.getMessage());

            // And it has to fail *within the budget it was given*. This is not a performance
            // assertion — it is the only way to notice if max.block.ms goes back to its default,
            // which bounds send()'s wait for metadata independently of the delivery timeout. At the
            // default the publisher holds the scheduler thread for a minute per batch for as long as
            // the broker is unreachable, and the only visible symptom is that this test got slower.
            assertTrue(
                    took.compareTo(budget.multipliedBy(3)) < 0,
                    "gave up after " + took.toMillis() + "ms on a " + budget.toMillis()
                            + "ms budget; check max.block.ms");
        }
    }

    @Test
    @DisplayName("an empty batch does not open a connection")
    void emptyBatchIsANoOp() {
        try (Producer<String, String> unreachable =
                KafkaProducers.create("127.0.0.1:1", Duration.ofSeconds(2), Map.of())) {
            // Would throw if it tried to send anything, since there is no broker.
            new KafkaEventPublisher(unreachable, "anything", Duration.ofSeconds(5)).publish(List.of());
        }
    }

    @Test
    @DisplayName("republishing the same batch is safe, and the duplicates are marked as such")
    void atLeastOnceIsSurvivable() {
        String topic = topic();
        List<OutboxEntry> batch = List.of(entry(1, reserved("r-dup", "halo", 1)));

        // Exactly what happens when the process dies between a successful send and the database
        // write that records it: the same batch goes again.
        publish(topic, batch);
        publish(topic, batch);
        List<ConsumerRecord<String, String>> got = drain(topic, 2);

        assertEquals(2, got.size(), "at least once, not exactly once — the broker saw it twice");
        assertEquals(
                header(got.get(0), KafkaEventPublisher.HEADER_DEDUPE_KEY),
                header(got.get(1), KafkaEventPublisher.HEADER_DEDUPE_KEY),
                "identical keys, so a consumer that has seen the first drops the second");
        assertEquals(got.get(0).value(), got.get(1).value());
    }

    @Test
    @DisplayName("nonsense configuration is refused at construction, not at the first send")
    void validatesItsArguments() {
        try (Producer<String, String> p = KafkaProducers.create("127.0.0.1:1", Duration.ofSeconds(2), Map.of())) {
            assertThrows(IllegalArgumentException.class, () -> new KafkaEventPublisher(p, "", Duration.ofSeconds(1)));
            assertThrows(IllegalArgumentException.class, () -> new KafkaEventPublisher(p, "t", Duration.ZERO));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new KafkaEventPublisher(p, "t", Duration.ofSeconds(-1)));
        }
    }

    // --- helpers -------------------------------------------------------------------------------

    private void publish(String topic, List<OutboxEntry> batch) {
        try (Producer<String, String> producer =
                KafkaProducers.create(kafka.getBootstrapServers(), TIMEOUT, Map.of())) {
            new KafkaEventPublisher(producer, topic, TIMEOUT).publish(batch);
        }
    }

    /** Reads from the beginning until {@code expected} records have arrived or the timeout passes. */
    private List<ConsumerRecord<String, String>> drain(String topic, int expected) {
        Properties config = new Properties();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        config.put(ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID());
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());

        List<ConsumerRecord<String, String>> collected = new ArrayList<>();
        Instant deadline = Instant.now().plus(TIMEOUT);
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(config)) {
            consumer.subscribe(List.of(topic));
            while (collected.size() < expected && Instant.now().isBefore(deadline)) {
                ConsumerRecords<String, String> polled = consumer.poll(Duration.ofMillis(500));
                polled.forEach(collected::add);
            }
        }
        assertFalse(collected.isEmpty(), "nothing arrived on " + topic);
        // Restore the order the outbox sent them in. Records land on several partitions, and poll
        // returns them partition by partition, so the arrival order is not the publish order — which
        // is exactly the property the class comment warns consumers about.
        collected.sort((a, b) -> Long.compare(sequence(a), sequence(b)));
        return collected;
    }

    private static long sequence(ConsumerRecord<String, String> record) {
        return Long.parseLong(header(record, KafkaEventPublisher.HEADER_SEQUENCE));
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    private static String topic() {
        return "till-events-" + UUID.randomUUID();
    }

    private static OutboxEntry entry(long sequence, Event event) {
        return new OutboxEntry(sequence, event, T0);
    }

    private static Event.StockReserved reserved(String id, String sku, long quantity) {
        return new Event.StockReserved(
                ReservationId.of(id), List.of(Line.of(sku, quantity)), T0.plus(Duration.ofMinutes(15)), T0);
    }

    private static IdempotencyKey key(String value) {
        return IdempotencyKey.of(value);
    }
}
