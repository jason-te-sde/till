package io.till.catalogue;

import io.micrometer.core.instrument.MeterRegistry;
import io.till.core.Codec;
import io.till.core.Event;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Reads till's events and hands them to the projection.
 *
 * <h2>Offsets are committed after the work, not before</h2>
 *
 * <p>Auto-commit would acknowledge a batch on a timer, including records this process has not
 * applied yet — so a crash loses them silently and the projection is permanently wrong with nothing
 * to indicate it. Committing manually, after {@link AvailabilityProjection} has committed its own
 * transaction, makes the failure mode the survivable one: a crash in between replays the batch, and
 * the inbox turns the replay into a no-op.
 *
 * <p>That is the same trade the publisher makes on the other side. Both ends choose "possibly twice"
 * over "possibly never", and the inbox is what makes "possibly twice" free.
 *
 * <h2>Why a thread and not an annotation</h2>
 *
 * <p>A plain consumer on a thread of its own, rather than spring-kafka, for the same reason
 * {@code till-jdbc} is plain JDBC: the behaviour that matters here is when the offset is committed
 * and what happens when the projection throws, and both are easier to be sure of when they are
 * eight lines in front of you than when they are a container's defaults.
 */
@Component
@Conditional(KafkaConfigured.class)
class EventConsumer {

    private static final Logger LOG = LoggerFactory.getLogger(EventConsumer.class);

    private final CatalogueProperties properties;
    private final AvailabilityProjection projection;
    private final MeterRegistry registry;

    private final AtomicBoolean running = new AtomicBoolean(true);
    private final CountDownLatch stopped = new CountDownLatch(1);
    private volatile KafkaConsumer<String, String> consumer;
    private Thread thread;

    EventConsumer(CatalogueProperties properties, AvailabilityProjection projection, MeterRegistry registry) {
        this.properties = properties;
        this.projection = projection;
        this.registry = registry;
    }

    /**
     * Starts once the application can serve requests.
     *
     * <p>On {@code ApplicationReadyEvent} rather than {@code @PostConstruct}: the projection needs a
     * migrated schema and a working pool, and starting the loop while the context is still being
     * built means the first event can arrive before either exists.
     */
    @EventListener(ApplicationReadyEvent.class)
    void start() {
        thread = new Thread(this::run, "catalogue-events");
        thread.setDaemon(true);
        thread.start();
    }

    private void run() {
        CatalogueProperties.Kafka kafka = properties.kafka();
        LOG.info("consuming {} from {} as {}", kafka.topic(), kafka.bootstrapServers(), kafka.groupId());
        try (KafkaConsumer<String, String> client = new KafkaConsumer<>(configFor(kafka))) {
            consumer = client;
            client.subscribe(List.of(kafka.topic()));
            while (running.get()) {
                ConsumerRecords<String, String> records = client.poll(kafka.pollTimeout());
                if (records.isEmpty()) {
                    continue;
                }
                for (ConsumerRecord<String, String> record : records) {
                    consume(record);
                }
                // After the projection committed, never before.
                client.commitSync();
            }
        } catch (WakeupException expected) {
            LOG.info("event consumer shutting down");
        } catch (RuntimeException e) {
            // Nothing restarts this. A projection that has stopped moving is worse than one that
            // never started, because the storefront keeps serving numbers that are quietly frozen —
            // so this is an error, and the counter is the thing to alert on.
            LOG.error("the event consumer stopped; availability will go stale until this is restarted", e);
            registry.counter("catalogue.consumer.failures").increment();
        } finally {
            stopped.countDown();
        }
    }

    private void consume(ConsumerRecord<String, String> record) {
        String dedupeKey = header(record, "till-dedupe-key");
        if (dedupeKey == null) {
            // Not ours, or a producer that predates the header. Skipping is right: without a
            // deduplication key there is no way to apply it exactly once, and applying a delta
            // twice is worse than not applying it.
            LOG.warn("record at {}:{} has no deduplication key; skipping", record.partition(), record.offset());
            registry.counter("catalogue.consumer.unusable").increment();
            return;
        }
        Event event = Codec.decodeEvent(record.value());
        long sequence = Long.parseLong(header(record, "till-sequence"));
        if (projection.apply(dedupeKey, sequence, event)) {
            registry.counter("catalogue.consumer.applied").increment();
        } else {
            registry.counter("catalogue.consumer.duplicates").increment();
        }
    }

    private static Properties configFor(CatalogueProperties.Kafka kafka) {
        Properties config = new Properties();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.bootstrapServers());
        config.put(ConsumerConfig.GROUP_ID_CONFIG, kafka.groupId());
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        // From the beginning, because a storefront joining a topic for the first time wants the
        // history that produced the current levels, not whatever happens next.
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        return config;
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    /** Wakes the poll so shutdown does not wait out the poll timeout. */
    @PreDestroy
    void stop() throws InterruptedException {
        running.set(false);
        KafkaConsumer<String, String> client = consumer;
        if (client != null) {
            client.wakeup();
        }
        if (thread != null) {
            stopped.await(Duration.ofSeconds(5).toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
        }
    }
}
