package io.till.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.errors.UnknownTopicOrPartitionException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

/** The ledger's topic, declared against a real broker: created, grown, and otherwise left alone. */
@Testcontainers
class KafkaTopicTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    private static KafkaContainer kafka;
    private static Admin admin;

    @BeforeAll
    static void startBroker() {
        // The JVM image, for the reason KafkaEventPublisherTest gives.
        kafka = new KafkaContainer("apache/kafka:4.1.0");
        kafka.start();
        admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers()));
    }

    @AfterAll
    static void stopBroker() {
        if (admin != null) {
            admin.close();
        }
        if (kafka != null) {
            kafka.stop();
        }
    }

    @Test
    @DisplayName("a topic that is not there is created with the partitions asked for")
    void creates() throws Exception {
        String name = name();

        assertEquals(12, new KafkaTopic(admin, name, 12, (short) 1, TIMEOUT).ensure());
        assertEquals(12, partitions(name));
    }

    @Test
    @DisplayName("one a broker made on its own, with one partition, is grown to the number")
    void grows() throws Exception {
        String name = name();
        admin.createTopics(List.of(new NewTopic(name, 1, (short) 1))).all().get();

        assertEquals(12, new KafkaTopic(admin, name, 12, (short) 1, TIMEOUT).ensure());
        assertEquals(12, partitions(name));
    }

    @Test
    @DisplayName("one with more is left alone, because partitions cannot be taken away")
    void leavesMoreAlone() throws Exception {
        String name = name();
        admin.createTopics(List.of(new NewTopic(name, 16, (short) 1))).all().get();

        assertEquals(16, new KafkaTopic(admin, name, 12, (short) 1, TIMEOUT).ensure());
        assertEquals(16, partitions(name));
    }

    @Test
    @DisplayName("declaring twice is declaring once, and a cluster that cannot be reached is an error, not a silence")
    void idempotentAndLoud() throws Exception {
        String name = name();
        KafkaTopic topic = new KafkaTopic(admin, name, 4, (short) 1, TIMEOUT);
        assertEquals(4, topic.ensure());
        assertEquals(4, topic.ensure());

        try (Admin nowhere = Admin.create(Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, "127.0.0.1:1",
                AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 2_000,
                AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 1_000))) {
            assertThrows(IllegalStateException.class, () -> new KafkaTopic(nowhere, name(), 4, (short) 1, Duration.ofSeconds(3)).ensure());
        }
    }

    /** What the broker says, once it knows: a topic just created can take a moment to be describable. */
    private static int partitions(String name) throws Exception {
        for (int attempt = 1; ; attempt++) {
            try {
                return admin.describeTopics(List.of(name)).allTopicNames().get().get(name).partitions().size();
            } catch (ExecutionException e) {
                if (!(e.getCause() instanceof UnknownTopicOrPartitionException) || attempt == 50) {
                    throw e;
                }
                Thread.sleep(100);
            }
        }
    }

    private static String name() {
        return "till.events." + UUID.randomUUID();
    }
}
