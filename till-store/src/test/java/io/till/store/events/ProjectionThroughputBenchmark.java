package io.till.store.events;

import io.micrometer.core.instrument.MeterRegistry;
import io.till.core.Event;
import io.till.core.Line;
import io.till.core.OutboxEntry;
import io.till.core.ReservationId;
import io.till.core.Sku;
import io.till.kafka.KafkaEventPublisher;
import io.till.kafka.KafkaProducers;
import io.till.kafka.KafkaTopic;
import io.till.store.StoreProperties;
import io.till.store.StoreTest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.producer.Producer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.testcontainers.kafka.KafkaContainer;

/**
 * How fast the store's consumers turn the event stream into its read models: a topic full of
 * checkouts, read by as many consumers as there are stores, until every event has been applied.
 *
 * <p>Not part of the build: it runs only with {@code -Dtill.benchmark=true} and asserts nothing. A
 * group reads a partition with one consumer, so the number to change is {@code -Dbench.partitions} —
 * 1, as a topic left to a broker's default has, against the 12 the ledger now declares — with
 * {@code bench.consumers} (4, the load test's stores) and {@code bench.events} (40,000).
 *
 * <pre>
 * mvn -q -pl till-store -am test -Dtest=ProjectionThroughputBenchmark -Dsurefire.failIfNoSpecifiedTests=false \
 *     -Dtill.benchmark=true -Dbench.partitions=1
 * </pre>
 */
@EnabledIfSystemProperty(named = "till.benchmark", matches = "true")
class ProjectionThroughputBenchmark extends StoreTest {

    private static final Instant AT = Instant.parse("2026-10-01T00:00:00Z");

    @Autowired
    Projector projector;

    @Autowired
    MeterRegistry meters;

    @Autowired
    StoreProperties properties;

    @Test
    void drains() throws Exception {
        int events = Integer.getInteger("bench.events", 40_000);
        int partitions = Integer.getInteger("bench.partitions", 12);
        int consumers = Integer.getInteger("bench.consumers", 4);
        String topic = "till.events.bench." + UUID.randomUUID();

        try (KafkaContainer kafka = new KafkaContainer("apache/kafka:4.1.0")) {
            kafka.start();
            try (Admin admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers()));
                    Producer<String, String> producer =
                            KafkaProducers.create(kafka.getBootstrapServers(), Duration.ofSeconds(30), Map.of())) {
                new KafkaTopic(admin, topic, partitions, (short) 1, Duration.ofSeconds(30)).ensure();
                publish(new KafkaEventPublisher(producer, topic, Duration.ofSeconds(30)), events);
            }

            StoreProperties reading = new StoreProperties(
                    properties.till(),
                    new StoreProperties.Kafka(kafka.getBootstrapServers(), topic, "bench-" + UUID.randomUUID(), Duration.ofMillis(200)),
                    properties.auth(), properties.checkout(), properties.demo(), properties.catalogue());
            double before = applied();
            long started = System.nanoTime();
            List<EventConsumer> stores = new ArrayList<>();
            for (int i = 0; i < consumers; i++) {
                EventConsumer consumer = new EventConsumer(reading, projector, meters);
                consumer.start();
                stores.add(consumer);
            }
            try {
                while (applied() - before < events) {
                    Thread.sleep(20);
                }
            } finally {
                for (EventConsumer consumer : stores) {
                    consumer.stop();
                }
            }
            double seconds = (System.nanoTime() - started) / 1e9;
            System.out.printf(Locale.ROOT, "%nEvents to the store's read models: %,d events, %d partitions, %d consumers%n",
                    events, partitions, consumers);
            System.out.printf(Locale.ROOT, "  applied in   %.1f s: %,.0f events a second%n", seconds, events / seconds);
        }
    }

    /** Half reservations and half their commits, one unit each over thirty-two games, in ledger order. */
    private static void publish(KafkaEventPublisher publisher, int events) {
        List<OutboxEntry> batch = new ArrayList<>();
        long sequence = 0;
        for (int i = 0; i < events / 2; i++) {
            ReservationId id = ReservationId.of("bench-" + i);
            List<Line> lines = List.of(new Line(Sku.of("game-" + (i % 32)), 1));
            batch.add(new OutboxEntry(++sequence, new Event.StockReserved(id, lines, AT.plus(Duration.ofMinutes(15)), AT), AT));
            batch.add(new OutboxEntry(++sequence, new Event.StockCommitted(id, lines, AT), AT));
            if (batch.size() >= 1_000) {
                publisher.publish(batch);
                batch.clear();
            }
        }
        publisher.publish(batch);
    }

    private double applied() {
        return meters.counter("store.consumer.applied").count();
    }
}
