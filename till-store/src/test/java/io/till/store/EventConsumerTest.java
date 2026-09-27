package io.till.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.till.core.Event;
import io.till.core.IdempotencyKey;
import io.till.core.Line;
import io.till.core.OutboxEntry;
import io.till.core.ReservationId;
import io.till.core.Sku;
import io.till.kafka.KafkaEventPublisher;
import io.till.kafka.KafkaProducers;
import io.till.store.catalogue.Availability;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.kafka.clients.producer.Producer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.kafka.KafkaContainer;

/**
 * The consuming half, end to end: the real publisher puts events on a real broker, and this
 * service's own loop projects them.
 *
 * <p>{@code ProjectorTest} proves the projection is right when handed an event. What only this can
 * prove is everything between: that the headers the publisher writes are the headers the consumer
 * reads, that {@code Codec} round-trips an event through Kafka unchanged, that the thread actually
 * starts, and that offsets are committed after the work rather than before. Those are the joins, and
 * every wiring bug this project has had lived in one.
 */
class EventConsumerTest extends StoreTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final String TOPIC = "till-events-consumer-test";

    /** The JVM image: the native one segfaults on hosts whose container UID has no passwd entry. */
    private static final String KAFKA_IMAGE = "apache/kafka:4.1.0";

    private static final KafkaContainer KAFKA = new KafkaContainer(KAFKA_IMAGE);

    static {
        KAFKA.start();
    }

    @DynamicPropertySource
    static void kafka(DynamicPropertyRegistry registry) {
        registry.add("store.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("store.kafka.topic", () -> TOPIC);
    }

    @Autowired
    Availability availability;

    @Test
    @DisplayName("events published to the topic turn into availability, with nobody calling the projection")
    void theLoopProjects() {
        Instant at = Instant.parse("2026-09-18T12:00:00Z");
        publish(
                new Event.StockAdjusted(IdempotencyKey.of("d1"), Sku.of("sunless-orbit"), 12, 12, 0, at),
                new Event.StockReserved(
                        ReservationId.of("r1"), List.of(Line.of("sunless-orbit", 4)), at.plus(Duration.ofMinutes(15)), at));

        // No call to the projection anywhere here. If the consumer thread never started, or the header
        // names drifted, or Codec cannot read back what it wrote, this times out.
        Availability.Level level = await("sunless-orbit", 8);

        assertEquals(12, level.onHand());
        assertEquals(4, level.reserved());
        assertEquals(at, level.updatedAt(), "the instant the ledger decided, carried all the way through");
    }

    @Test
    @DisplayName("a batch published twice leaves the numbers where the first one put them")
    void redeliveryThroughTheLoopIsSafe() {
        Instant at = Instant.parse("2026-09-18T12:00:00Z");
        Event adjusted = new Event.StockAdjusted(IdempotencyKey.of("d2"), Sku.of("rust-and-rain"), 6, 6, 0, at);
        Event reserved = new Event.StockReserved(
                ReservationId.of("r2"), List.of(Line.of("rust-and-rain", 2)), at.plus(Duration.ofMinutes(15)), at);

        publish(adjusted, reserved);
        await("rust-and-rain", 4);
        // Exactly what a publisher that died between the send and its own bookkeeping does next.
        publish(adjusted, reserved);

        // Give the loop time to actually consume the repeats, so this cannot pass by not having looked.
        Instant deadline = Instant.now().plusSeconds(5);
        while (Instant.now().isBefore(deadline)) {
            assertEquals(4, availability.of("rust-and-rain").orElseThrow().available());
        }
        assertEquals(2, availability.of("rust-and-rain").orElseThrow().reserved());
    }

    @Test
    @DisplayName("the store still answers when the broker has told it nothing about a game")
    void unknownSkusReadAsSoldOut() {
        assertTrue(availability.of("ledger-of-tides").isEmpty());
    }

    private void publish(Event... events) {
        try (Producer<String, String> producer = KafkaProducers.create(KAFKA.getBootstrapServers(), TIMEOUT, Map.of())) {
            // The real publisher, not a hand-rolled record: the headers under test are the ones it writes.
            KafkaEventPublisher publisher = new KafkaEventPublisher(producer, TOPIC, TIMEOUT);
            long sequence = 1;
            for (Event event : events) {
                publisher.publish(List.of(new OutboxEntry(sequence++, event, event.occurredAt())));
            }
        }
    }

    private Availability.Level await(String sku, long available) {
        Instant deadline = Instant.now().plus(TIMEOUT);
        Optional<Availability.Level> last = Optional.empty();
        while (Instant.now().isBefore(deadline)) {
            last = availability.of(sku);
            if (last.isPresent() && last.get().available() == available) {
                return last.get();
            }
            Thread.onSpinWait();
        }
        throw new AssertionError("availability for " + sku + " never reached " + available + "; last was "
                + last.map(Object::toString).orElse("nothing"));
    }
}
