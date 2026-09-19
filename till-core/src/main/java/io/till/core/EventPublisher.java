package io.till.core;

import java.util.List;

/**
 * Where outbox entries go.
 *
 * <p>The useful thing about a transactional outbox is that the destination is somebody else's
 * decision. Kafka, SNS, a webhook, another service's HTTP API: all of them are "take this batch and
 * tell me it arrived", and none of them needs a change to the ledger. {@code till-kafka} is one
 * implementation and the server falls back to a log line when there is none.
 *
 * <p>Declared here rather than beside the server that drives it, so that an implementation depends
 * on the kernel and not on an application. A publisher is an adapter on the same side of the
 * boundary as {@link Ledger}: the direction of the dependency is the whole point of having a port.
 *
 * <p>Delivery is <b>at least once</b>. An implementation is allowed to deliver a batch and then fail
 * before it returns, in which case the same batch is delivered again; every event carries a
 * {@link Event#dedupeKey()} for the consumer to drop the second copy with. An
 * implementation that throws has its batch retried, so throwing is the correct response to a broker
 * that is down.
 */
public interface EventPublisher {

    /**
     * Delivers a batch, in sequence order.
     *
     * @param entries the entries to deliver
     * @throws RuntimeException if delivery failed, in which case the batch is retried
     */
    void publish(List<OutboxEntry> entries);
}
