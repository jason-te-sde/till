package io.till.catalogue;

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
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.kafka.clients.producer.Producer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The consuming half, end to end: the real publisher puts events on a real broker, and this
 * service's own loop projects them.
 *
 * <p>{@link AvailabilityProjectionTest} proves the projection is right when handed an event. What
 * only this can prove is everything between: that the headers the publisher writes are the headers
 * the consumer reads, that {@code Codec} round-trips an event through Kafka unchanged, that the
 * thread actually starts, and that offsets are committed after the work rather than before.
 *
 * <p>Those are the joins, and every wiring bug this project has had lived in one.
 */
@SpringBootTest
class EventConsumerTest {

    private static final Instant T0 = Instant.parse("2026-09-18T12:00:00Z");
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final String TOPIC = "till-events-consumer-test";

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");
    private static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka-native:4.1.0");

    static {
        POSTGRES.start();
        KAFKA.start();
    }

    @DynamicPropertySource
    static void environment(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("catalogue.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("catalogue.kafka.topic", () -> TOPIC);
    }

    @Autowired
    AvailabilityProjection projection;

    @Test
    @DisplayName("events published to the topic turn into availability, with nobody calling the projection")
    void theLoopProjects() {
        publish(
                new Event.StockAdjusted(IdempotencyKey.of("d1"), Sku.of("sunless-orbit"), 12, 12, 0, T0),
                new Event.StockReserved(
                        ReservationId.of("r1"),
                        List.of(Line.of("sunless-orbit", 4)),
                        T0.plus(Duration.ofMinutes(15)),
                        T0));

        // No call to projection.apply anywhere here. If the consumer thread never started, or the
        // header names drifted, or Codec cannot read back what it wrote, this times out.
        AvailabilityProjection.Availability level = await("sunless-orbit", 8);

        assertEquals(12, level.onHand());
        assertEquals(4, level.reserved());
        assertEquals(8, level.available());
        assertEquals(T0, level.updatedAt(), "the instant till decided, carried all the way through");
    }

    @Test
    @DisplayName("a batch published twice leaves the numbers where the first one put them")
    void redeliveryThroughTheLoopIsSafe() {
        Event adjusted = new Event.StockAdjusted(IdempotencyKey.of("d2"), Sku.of("rust-and-rain"), 6, 6, 0, T0);
        Event reserved =
                new Event.StockReserved(
                        ReservationId.of("r2"),
                        List.of(Line.of("rust-and-rain", 2)),
                        T0.plus(Duration.ofMinutes(15)),
                        T0);

        publish(adjusted, reserved);
        await("rust-and-rain", 4);
        // Exactly what a publisher that died between the send and its own bookkeeping does next.
        publish(adjusted, reserved);

        // Give the loop time to actually consume the repeats, so this cannot pass by not having
        // looked yet.
        Instant deadline = Instant.now().plusSeconds(5);
        while (Instant.now().isBefore(deadline)) {
            assertEquals(4, projection.of("rust-and-rain").orElseThrow().available());
        }
        assertEquals(2, projection.of("rust-and-rain").orElseThrow().reserved());
    }

    private void publish(Event... events) {
        try (Producer<String, String> producer =
                KafkaProducers.create(KAFKA.getBootstrapServers(), TIMEOUT, Map.of())) {
            // The real publisher, not a hand-rolled record. The headers under test are the ones it
            // writes, so writing them here instead would test this test.
            KafkaEventPublisher publisher = new KafkaEventPublisher(producer, TOPIC, TIMEOUT);
            long sequence = 1;
            for (Event event : events) {
                publisher.publish(List.of(new OutboxEntry(sequence++, event, T0)));
            }
        }
    }

    private AvailabilityProjection.Availability await(String sku, long available) {
        Instant deadline = Instant.now().plus(TIMEOUT);
        Optional<AvailabilityProjection.Availability> last = Optional.empty();
        while (Instant.now().isBefore(deadline)) {
            last = projection.of(sku);
            if (last.isPresent() && last.get().available() == available) {
                return last.get();
            }
        }
        throw new AssertionError(
                "availability for " + sku + " never reached " + available + "; last was "
                        + last.map(Object::toString).orElse("nothing"));
    }

    @Test
    @DisplayName("the shop still answers when the broker has told it nothing about a game")
    void unknownSkusReadAsSoldOut() {
        // Not an error and not unlimited: a storefront that has heard nothing should offer nothing,
        // and let till be the one to say otherwise at checkout.
        assertTrue(projection.of("ledger-of-tides").isEmpty());
    }
}
