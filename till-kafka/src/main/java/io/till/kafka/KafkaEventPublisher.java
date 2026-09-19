package io.till.kafka;

import io.till.core.Codec;
import io.till.core.Event;
import io.till.core.EventPublisher;
import io.till.core.OutboxEntry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sends the outbox to Kafka.
 *
 * <p>The outbox already solved the hard half: an event is in the same transaction as the change it
 * describes, so there is no window where stock moved and nobody downstream will hear. What is left
 * is getting it onto a broker without inventing a second source of truth, and that is this class.
 *
 * <h2>Why the batch is all-or-nothing to the caller</h2>
 *
 * <p>{@link #publish(List)} sends every record, flushes, and then checks every acknowledgement. If
 * any one failed it throws, and {@code OutboxPublisher} marks <b>nothing</b> as published — so the
 * whole batch is offered again, including the records that did arrive. That is deliberate. The
 * alternative, marking the ones that succeeded, means tracking per-record state in the outbox to buy
 * a property the consumer cannot rely on anyway: delivery is at least once whatever we do here,
 * because a crash between a successful send and the database write repeats the send regardless.
 * Duplicates are the consumer's to drop, and every record carries the key to drop them with.
 *
 * <h2>What ordering a consumer actually gets</h2>
 *
 * <p>Kafka orders records within a partition, and the partition follows the key. The key here is
 * <b>the thing the event is about</b> — the reservation for the four reservation events, the SKU for
 * an adjustment — so a consumer sees one reservation's lifecycle in order, and one SKU's adjustments
 * in order. It does <b>not</b> see a global order: a reservation on {@code widget} and an adjustment
 * to {@code widget} can land on different partitions. The outbox's monotonic sequence is on every
 * record as a header for a consumer that needs to detect or repair that.
 *
 * <p>Choosing the key with an exhaustive switch over the sealed {@link Event} is not style. Add a
 * sixth event and this stops compiling, which is the only mechanism that reliably stops a new event
 * type from silently defaulting to a null key and round-robin partitioning — the failure mode being
 * lost ordering, visible to nobody until a consumer's state goes wrong.
 */
public final class KafkaEventPublisher implements EventPublisher {

    private static final Logger LOG = LoggerFactory.getLogger(KafkaEventPublisher.class);

    /** Kafka's own header prefix convention is to have none; these are namespaced so they cannot clash. */
    static final String HEADER_DEDUPE_KEY = "till-dedupe-key";

    static final String HEADER_SEQUENCE = "till-sequence";
    static final String HEADER_TYPE = "till-event-type";
    static final String HEADER_OCCURRED_AT = "till-occurred-at";

    private final Producer<String, String> producer;
    private final String topic;
    private final Duration flushTimeout;

    /**
     * @param producer the producer to send with; this class does not close it, because its lifetime
     *     belongs to whatever configured it
     * @param topic the topic every event goes to
     * @param flushTimeout how long to wait for the batch's acknowledgements before giving up and
     *     letting the batch be retried
     */
    public KafkaEventPublisher(Producer<String, String> producer, String topic, Duration flushTimeout) {
        if (topic == null || topic.isBlank()) {
            throw new IllegalArgumentException("a topic is required");
        }
        if (flushTimeout.isNegative() || flushTimeout.isZero()) {
            throw new IllegalArgumentException("the flush timeout must be positive, got " + flushTimeout);
        }
        this.producer = producer;
        this.topic = topic;
        this.flushTimeout = flushTimeout;
    }

    @Override
    public void publish(List<OutboxEntry> entries) {
        if (entries.isEmpty()) {
            return;
        }
        // Sent first, waited on second. Sending one and waiting for its acknowledgement before
        // sending the next turns a batch of two hundred into two hundred round trips, which is the
        // single most common way a correct outbox publisher ends up too slow to keep up.
        List<Future<RecordMetadata>> sent = new ArrayList<>(entries.size());
        for (OutboxEntry entry : entries) {
            sent.add(producer.send(recordFor(entry)));
        }
        producer.flush();

        for (int i = 0; i < sent.size(); i++) {
            try {
                sent.get(i).get(flushTimeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new PublishException("interrupted while publishing " + entries.get(i).dedupeKey(), e);
            } catch (ExecutionException | TimeoutException e) {
                // Throwing is the contract: the batch is offered again next interval. Naming the
                // entry matters because the ones before it in this batch did arrive, and whoever
                // reads this line should expect those as duplicates rather than as a second bug.
                throw new PublishException(
                        "publishing " + entries.get(i).dedupeKey() + " failed; the whole batch will be retried", e);
            }
        }
        LOG.debug("published {} events to {}", entries.size(), topic);
    }

    private ProducerRecord<String, String> recordFor(OutboxEntry entry) {
        ProducerRecord<String, String> record =
                new ProducerRecord<>(topic, partitionKey(entry.event()), Codec.encodeEvent(entry.event()));
        for (Header header : headersFor(entry)) {
            record.headers().add(header);
        }
        return record;
    }

    /**
     * The entity the event is about, which is what a consumer needs ordered.
     *
     * <p>Exhaustive over the sealed interface on purpose — see the class comment.
     */
    private static String partitionKey(Event event) {
        return switch (event) {
            case Event.StockReserved e -> e.reservationId().value();
            case Event.StockCommitted e -> e.reservationId().value();
            case Event.StockReleased e -> e.reservationId().value();
            case Event.StockExpired e -> e.reservationId().value();
            case Event.StockAdjusted e -> e.sku().value();
        };
    }

    private static List<Header> headersFor(OutboxEntry entry) {
        return List.of(
                header(HEADER_DEDUPE_KEY, entry.dedupeKey()),
                header(HEADER_SEQUENCE, Long.toString(entry.sequence())),
                header(HEADER_TYPE, entry.event().getClass().getSimpleName()),
                header(HEADER_OCCURRED_AT, entry.event().occurredAt().toString()));
    }

    private static Header header(String name, String value) {
        return new RecordHeader(name, value.getBytes(StandardCharsets.UTF_8));
    }

    /** A batch that did not get through. Unchecked, because the port's contract is to throw. */
    public static final class PublishException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        PublishException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
