package io.till.server;

import io.till.core.EventPublisher;
import io.till.kafka.KafkaEventPublisher;
import io.till.kafka.KafkaProducers;
import io.till.kafka.KafkaTopic;
import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.producer.Producer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;

/**
 * Publishes the outbox to Kafka, when a broker is configured.
 *
 * <p>Conditional on {@code till.kafka.bootstrap-servers} having a <b>non-empty</b> value — see
 * {@link KafkaConfigured} for why that needed a class rather than an annotation. With nothing set
 * there is no producer, no connection attempt and no bean, so {@code OutboxPublisher} falls back to
 * log lines, which is what makes running till without a broker the default rather than a special
 * case.
 *
 * <p>The producer is a bean so that Spring closes it at shutdown. Closing it flushes whatever is
 * still buffered; not closing it would drop the tail of the last batch on every restart, and the
 * outbox rows for those events are already marked published.
 */
@Configuration
@Conditional(KafkaConfigured.class)
class KafkaConfiguration {

    private static final Logger LOG = LoggerFactory.getLogger(KafkaConfiguration.class);

    /**
     * @param properties the configured broker list, topic and producer overrides
     * @return the producer, closed by Spring at shutdown
     */
    @Bean(destroyMethod = "close")
    Producer<String, String> kafkaProducer(TillProperties properties) {
        TillProperties.Kafka kafka = properties.kafka();
        LOG.info("publishing the outbox to kafka topic {} at {}", kafka.topic(), kafka.bootstrapServers());
        return KafkaProducers.create(kafka.bootstrapServers(), kafka.deliveryTimeout(), kafka.properties());
    }

    /**
     * @param properties the configured broker list, and the settings a secured cluster needs
     * @return the client that declares the topic, closed by Spring at shutdown
     */
    @Bean(destroyMethod = "close")
    Admin kafkaAdmin(TillProperties properties) {
        TillProperties.Kafka kafka = properties.kafka();
        Map<String, Object> config = new HashMap<>(kafka.properties());
        config.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.bootstrapServers());
        return Admin.create(config);
    }

    /**
     * The publisher, which declares the topic before the first batch it sends.
     *
     * <p>Before every batch until that has worked once, and the batch fails while it has not: so the
     * first record never reaches a topic a broker created on its own, with its default of one
     * partition, and a broker that is not up yet costs the publisher a few rounds rather than the
     * service its start.
     *
     * @param producer the producer to send with
     * @param admin the client that declares the topic
     * @param properties the configured topic, its partitions and the delivery budget
     * @return the publisher {@code OutboxPublisher} will find and use instead of the log-line one
     */
    @Bean
    EventPublisher kafkaEventPublisher(Producer<String, String> producer, Admin admin, TillProperties properties) {
        TillProperties.Kafka kafka = properties.kafka();
        KafkaTopic topic =
                new KafkaTopic(admin, kafka.topic(), kafka.partitions(), kafka.replicationFactor(), kafka.deliveryTimeout());
        KafkaEventPublisher publisher = new KafkaEventPublisher(producer, kafka.topic(), kafka.deliveryTimeout());
        return entries -> {
            topic.ensure();
            publisher.publish(entries);
        };
    }
}
