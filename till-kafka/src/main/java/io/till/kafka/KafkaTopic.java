package io.till.kafka;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewPartitions;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.common.errors.TopicExistsException;
import org.apache.kafka.common.errors.UnknownTopicOrPartitionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The topic the outbox goes to, with as many partitions as its consumers need.
 *
 * <p>Partitions are how many consumers can work at once: a group reads one partition with one
 * consumer, however many members it has. A topic left to a broker's auto-creation gets the broker's
 * default, which is one — and that is how the first load tests ran four stores, three of them reading
 * nothing. So the ledger says what it publishes to, rather than leaving it to whichever client
 * happened to touch the topic first.
 *
 * <p>{@link #ensure} creates the topic if there is none, grows it if it has fewer partitions than
 * asked, and leaves one with more alone, because partitions cannot be taken away. Growing a topic
 * sends some keys to new partitions, so for a moment one reservation's next event can be read before
 * its last; the store's projection tolerates that, because its deltas commute and its inbox applies
 * each event once. A replication factor that differs from an existing topic's is only reported:
 * changing it is a reassignment, which is an operator's job and not a startup's.
 *
 * <p>Once it has succeeded it does nothing more, and until then it throws, so a publisher that calls
 * it before every batch never sends a record to a topic that is not yet what it should be.
 */
public final class KafkaTopic {

    private static final Logger LOG = LoggerFactory.getLogger(KafkaTopic.class);

    private final Admin admin;
    private final String name;
    private final int partitions;
    private final short replicationFactor;
    private final Duration timeout;
    private volatile boolean declared;

    /**
     * @param admin an admin client for the cluster; this class does not close it
     * @param name the topic
     * @param partitions how many partitions at least
     * @param replicationFactor how many copies of each, for a topic this creates
     * @param timeout how long each call to the cluster may take
     */
    public KafkaTopic(Admin admin, String name, int partitions, short replicationFactor, Duration timeout) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("a topic is required");
        }
        if (partitions < 1 || replicationFactor < 1) {
            throw new IllegalArgumentException(
                    "a topic needs at least one partition and one replica, got " + partitions + " and " + replicationFactor);
        }
        this.admin = admin;
        this.name = name;
        this.partitions = partitions;
        this.replicationFactor = replicationFactor;
        this.timeout = timeout;
    }

    /**
     * Makes sure the topic exists with at least the partitions asked for.
     *
     * <p>A topic somebody else has just created can exist and not yet be describable — the broker
     * says it exists when asked to create it, and does not know it when asked what it is — so a
     * creation that loses that race describes again, until the timeout, rather than assuming.
     *
     * @return how many partitions it has
     * @throws IllegalStateException if the cluster could not be asked, or refused
     */
    public synchronized int ensure() {
        if (declared) {
            return partitions;
        }
        long giveUpAt = System.nanoTime() + timeout.toNanos();
        try {
            while (true) {
                Optional<TopicDescription> found = describe();
                if (found.isPresent()) {
                    int have = found.get().partitions().size();
                    if (have < partitions) {
                        await(admin.createPartitions(Map.of(name, NewPartitions.increaseTo(partitions))).all());
                        LOG.info("grew {} from {} to {} partitions", name, have, partitions);
                        have = partitions;
                    }
                    declared = true;
                    return have;
                }
                if (create()) {
                    declared = true;
                    return partitions;
                }
                if (System.nanoTime() > giveUpAt) {
                    throw new IllegalStateException(
                            "the topic " + name + " exists and could not be described within " + timeout);
                }
                Thread.sleep(100);
            }
        } catch (ExecutionException e) {
            throw new IllegalStateException("could not declare the topic " + name + ": " + e.getCause(), e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted declaring the topic " + name, e);
        }
    }

    private Optional<TopicDescription> describe() throws ExecutionException {
        try {
            Map<String, TopicDescription> found = await(admin.describeTopics(List.of(name)).allTopicNames());
            TopicDescription description = found.get(name);
            short replicas = (short) description.partitions().get(0).replicas().size();
            if (replicas != replicationFactor) {
                LOG.warn("{} has {} replicas of each partition and {} were asked for; that is a reassignment, left to an operator",
                        name, replicas, replicationFactor);
            }
            return Optional.of(description);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof UnknownTopicOrPartitionException) {
                return Optional.empty();
            }
            throw e;
        }
    }

    /**
     * Creates the topic.
     *
     * @return true if this call created it; false if somebody else already had, which the caller
     *     finds out about by describing it
     */
    private boolean create() throws ExecutionException {
        try {
            await(admin.createTopics(List.of(new NewTopic(name, partitions, replicationFactor))).all());
            LOG.info("created {} with {} partitions and {} replicas of each", name, partitions, replicationFactor);
            return true;
        } catch (ExecutionException e) {
            if (e.getCause() instanceof TopicExistsException) {
                return false;
            }
            throw e;
        }
    }

    private <T> T await(Future<T> future) throws ExecutionException {
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted declaring the topic " + name, e);
        } catch (TimeoutException e) {
            throw new IllegalStateException("the cluster did not answer within " + timeout + " about the topic " + name, e);
        }
    }
}
