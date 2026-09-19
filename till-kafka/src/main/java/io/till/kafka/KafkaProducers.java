package io.till.kafka;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;

/**
 * The producer settings that decide whether delivery is actually safe.
 *
 * <p>A factory rather than a starter's defaults, because three of these settings are the difference
 * between "events reach Kafka" and "events reach Kafka unless something goes wrong", and a default
 * inherited from somewhere else is a default nobody has read. They are written out with the reason
 * attached.
 */
public final class KafkaProducers {

    /**
     * A few milliseconds of waiting turns a batch of two hundred single-record requests into a
     * handful of full ones. The publisher already has the whole batch in hand, so this costs latency
     * nobody is measuring and saves round trips everybody is paying for.
     */
    private static final int LINGER_MS = 5;

    /** Kafka's own default, and the cap: a longer delivery budget should not mean a longer wait per attempt. */
    private static final int MAX_REQUEST_TIMEOUT_MS = 30_000;

    /** Below this there is no room for even one attempt plus the linger. */
    private static final int MIN_DELIVERY_TIMEOUT_MS = LINGER_MS + 1_000;

    private KafkaProducers() {}

    /**
     * A producer configured for an outbox.
     *
     * @param bootstrapServers the broker list
     * @param deliveryTimeout how long a single record may spend being retried before the send is
     *     failed; this, not a retry count, is the real bound on how long a batch can hang. The
     *     per-attempt timeout is derived from it, so a short budget is honoured rather than refused
     * @param overrides extra producer properties, applied last, so a deployment can change anything
     *     here without a code change
     * @return a producer the caller owns and must close
     * @throws IllegalArgumentException if the budget leaves no room for one attempt
     */
    public static Producer<String, String> create(
            String bootstrapServers, Duration deliveryTimeout, Map<String, String> overrides) {
        Map<String, Object> config = new HashMap<>();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());

        // Every in-sync replica must have the record before it counts as sent. `acks=1` means the
        // leader alone, and a leader that dies before its followers catch up loses the record
        // silently — the producer was told it succeeded, so the outbox row is marked published and
        // the event is gone for good. This is the setting that makes the outbox worth having.
        config.put(ProducerConfig.ACKS_CONFIG, "all");

        // Without this, a retry after a timed-out-but-actually-successful send writes the record
        // twice *at the broker*. The outbox already forces the consumer to be idempotent, so this is
        // not correctness — it is not manufacturing duplicates the consumer then has to pay to drop.
        config.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);

        // Five, not one. Idempotence makes concurrent in-flight batches safe to reorder-and-retry,
        // so the usual reason to pin this to 1 (preserving order under retry) does not apply, and
        // pinning it would cost most of the throughput of a batch.
        config.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);

        // Kafka requires delivery.timeout.ms >= linger.ms + request.timeout.ms, and rejects the
        // producer at construction if it is not. Left to the defaults, a caller who asks to give up
        // after two seconds gets an exception naming request.timeout.ms — a setting they never
        // touched and probably have no opinion about. So the per-attempt timeout is derived from the
        // budget rather than inherited: ask for two seconds and you get two seconds.
        long deliveryMs = deliveryTimeout.toMillis();
        if (deliveryMs < MIN_DELIVERY_TIMEOUT_MS) {
            throw new IllegalArgumentException(
                    "the delivery timeout must be at least " + MIN_DELIVERY_TIMEOUT_MS + "ms, got " + deliveryMs
                            + "ms; below that there is no room for a single attempt");
        }
        config.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, (int) deliveryMs);
        config.put(
                ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG,
                (int) Math.min(MAX_REQUEST_TIMEOUT_MS, deliveryMs - LINGER_MS));
        // And the one that is easy to miss, because it does not sound like a timeout: max.block.ms
        // bounds how long send() itself blocks waiting for cluster metadata, and it defaults to a
        // minute independently of the delivery budget. Left alone, a caller asking to give up after
        // two seconds instead sits in send() for sixty — which for this publisher means the
        // scheduler thread is held for a minute per batch for as long as the broker is unreachable,
        // rather than failing fast and retrying on the next tick. Measured: a test against a dead
        // broker went from 60s to 2s.
        config.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, (int) deliveryMs);

        config.put(ProducerConfig.LINGER_MS_CONFIG, LINGER_MS);
        config.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "lz4");

        overrides.forEach(config::put);
        return new KafkaProducer<>(config);
    }
}
