package io.till.server;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.till.core.Codec;
import io.till.core.Event;
import io.till.core.EventPublisher;
import io.till.core.IdempotencyKey;
import io.till.core.Sku;
import io.till.jdbc.JdbcLedger;
import io.till.jdbc.JdbcSchema;
import io.till.kafka.KafkaEventPublisher;
import io.till.kafka.KafkaProducers;
import io.till.kafka.KafkaTopic;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.testcontainers.kafka.KafkaContainer;

/**
 * How fast the outbox reaches Kafka: a backlog of events in PostgreSQL, publishers on it as the
 * ledger's instances would run them, and a real broker, until everything has been delivered.
 *
 * <p>Not part of the build. It runs only with {@code -Dtill.benchmark=true} and asserts nothing; it is
 * how the publisher's throughput was measured before and after it learned to drain (ADR 6, "Later").
 * {@code -Dbench.events} (50,000), {@code bench.batch}, {@code bench.passes} and
 * {@code bench.publishers} (2) change the shape; a batch of 200 and one pass is the cadence the
 * publisher used to have.
 *
 * <pre>
 * mvn -q -pl till-server -am test -Dtest=OutboxThroughputBenchmark -Dsurefire.failIfNoSpecifiedTests=false \
 *     -Dtill.benchmark=true -Dbench.batch=200 -Dbench.passes=1
 * </pre>
 */
@EnabledIfSystemProperty(named = "till.benchmark", matches = "true")
@ExtendWith(RequiresDatabase.class)
@ResourceLock("till-database")
class OutboxThroughputBenchmark {

    private static final String TOPIC = "till.events.bench";
    private static final Duration INTERVAL = Duration.ofSeconds(1);

    @Test
    void drains() throws Exception {
        int events = Integer.getInteger("bench.events", 50_000);
        int batch = Integer.getInteger("bench.batch", 500);
        int passes = Integer.getInteger("bench.passes", 20);
        int publishers = Integer.getInteger("bench.publishers", 2);

        try (KafkaContainer kafka = new KafkaContainer("apache/kafka:4.1.0")) {
            kafka.start();
            HikariConfig config = new HikariConfig();
            config.setJdbcUrl(TestDatabase.url());
            config.setUsername(TestDatabase.username());
            config.setPassword(TestDatabase.password());
            config.setMaximumPoolSize(8);
            try (HikariDataSource pool = new HikariDataSource(config);
                    Producer<String, String> producer =
                            KafkaProducers.create(kafka.getBootstrapServers(), Duration.ofSeconds(30), Map.of());
                    Admin admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers()))) {
                JdbcSchema.drop(pool);
                JdbcSchema.create(pool);
                fill(pool, events);
                JdbcLedger ledger = new JdbcLedger(pool);

                // Declared before the clock starts, so that what is timed is the stream and not the
                // broker creating a topic.
                KafkaTopic topic = new KafkaTopic(admin, TOPIC, 12, (short) 1, Duration.ofSeconds(30));
                topic.ensure();
                KafkaEventPublisher toKafka = new KafkaEventPublisher(producer, TOPIC, Duration.ofSeconds(30));
                DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
                beans.registerSingleton("publisher", (EventPublisher) toKafka::publish);
                SimpleMeterRegistry registry = new SimpleMeterRegistry();
                TillProperties properties = properties(batch, passes);

                AtomicBoolean done = new AtomicBoolean();
                long started = System.nanoTime();
                try (ExecutorService instances = Executors.newFixedThreadPool(publishers)) {
                    List<Future<?>> running = new ArrayList<>();
                    for (int i = 0; i < publishers; i++) {
                        OutboxPublisher publisher = new OutboxPublisher(
                                ledger, beans.getBeanProvider(EventPublisher.class), properties, Clock.systemUTC(), registry);
                        // What the scheduler does: a run, then the interval, then another.
                        running.add(instances.submit(() -> {
                            while (!done.get()) {
                                publisher.drain();
                                Thread.sleep(INTERVAL);
                            }
                            return null;
                        }));
                    }
                    while (ledger.backlog() > 0) {
                        Thread.sleep(20);
                    }
                    long elapsed = System.nanoTime() - started;
                    done.set(true);
                    for (Future<?> future : running) {
                        future.get();
                    }

                    Delivered delivered = count(kafka.getBootstrapServers());
                    double seconds = elapsed / 1e9;
                    System.out.printf(Locale.ROOT, "%nOutbox to Kafka: %,d events, %d publishers, batch %d, %d passes a run, %s between runs%n",
                            events, publishers, batch, passes, INTERVAL);
                    System.out.printf(Locale.ROOT, "  drained in         %.1f s: %,.0f events a second%n", seconds, events / seconds);
                    System.out.printf(Locale.ROOT, "  on the topic       %,d records, %,d of them distinct%n",
                            delivered.records(), delivered.distinct());
                    System.out.printf(Locale.ROOT, "  rounds sat out     %,.0f%n", registry.counter("till.outbox.standby").count());
                }
            }
        }
    }

    /** {@code events} adjustments, written straight into the outbox in one statement. */
    private static void fill(HikariDataSource pool, int events) throws Exception {
        String template = Codec.encodeEvent(new Event.StockAdjusted(
                IdempotencyKey.of("KEY"), Sku.of("widget"), 1, 1, 0, Instant.parse("2026-10-01T00:00:00Z")));
        try (Connection connection = pool.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "insert into till_outbox (dedupe_key, payload, recorded_at) "
                                + "select 'adjusted:bench-' || g, replace(?, 'key=KEY', 'key=bench-' || g), now() "
                                + "from generate_series(1, ?) g")) {
            statement.setString(1, template);
            statement.setInt(2, events);
            statement.executeUpdate();
        }
    }

    private record Delivered(long records, long distinct) {}

    /** Everything on the topic: read until it has been quiet for three seconds. */
    private static Delivered count(String bootstrapServers) {
        Properties config = new Properties();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        config.put(ConsumerConfig.GROUP_ID_CONFIG, "bench-" + UUID.randomUUID());
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        long records = 0;
        Set<String> keys = new HashSet<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(config)) {
            consumer.subscribe(List.of(TOPIC));
            for (int quiet = 0; quiet < 6; ) {
                var polled = consumer.poll(Duration.ofMillis(500));
                quiet = polled.isEmpty() ? quiet + 1 : 0;
                for (ConsumerRecord<String, String> record : polled) {
                    Header key = record.headers().lastHeader("till-dedupe-key");
                    keys.add(new String(key.value(), StandardCharsets.UTF_8));
                    records++;
                }
            }
        }
        return new Delivered(records, keys.size());
    }

    private static TillProperties properties(int batch, int passes) {
        return new TillProperties(
                8,
                32,
                Duration.ofMinutes(15),
                Duration.ofHours(24),
                true,
                new TillProperties.Auth("", ""),
                new TillProperties.Sweeper(false, Duration.ofSeconds(5), 200, 10),
                new TillProperties.Outbox(true, INTERVAL, batch, passes),
                new TillProperties.Kafka("", TOPIC, Duration.ofSeconds(30), Map.of(), 12, (short) 1),
                new TillProperties.RetentionPolicy(
                        false, Duration.ofHours(1), 1000, 20, Duration.ofDays(7), Duration.ofDays(30), Duration.ZERO),
                new TillProperties.Batch(false, 64, 1024));
    }
}
