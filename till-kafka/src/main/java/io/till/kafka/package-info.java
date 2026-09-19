/**
 * Publishes the transactional outbox to Kafka.
 *
 * <p>One class does the work — {@link io.till.kafka.KafkaEventPublisher} — and one supplies the
 * producer settings that decide whether delivery is safe, {@link io.till.kafka.KafkaProducers}.
 *
 * <p>No Spring here, and no dependency on {@code till-server}. An adapter depends on the kernel and
 * never on the application that drives it, which is what lets the same publisher run from a plain
 * main method, from a test with a container, or from the service.
 *
 * <p>What a consumer is promised: <b>at least once</b> delivery, a stable
 * {@code till-dedupe-key} header to drop the repeats with, and ordering <i>per entity</i> — one
 * reservation's lifecycle, or one SKU's adjustments — because the record key is the entity rather
 * than anything about the batch. There is no global order; the outbox's sequence is on every record
 * for a consumer that needs to notice.
 */
package io.till.kafka;
