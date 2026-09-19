package io.till.server;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * True only when a broker list has actually been given.
 *
 * <p>A hand-written condition rather than {@code @ConditionalOnProperty}, because that annotation
 * asks whether the property is <b>present</b>, and {@code bootstrap-servers: ${TILL_KAFKA_BROKERS:}}
 * in {@code application.yml} is present on every deployment — it resolves to the empty string when
 * the variable is unset. So the condition matched, the producer was built with no brokers, and
 * Kafka's own config validation failed the bean: <b>the service refused to start by default</b>,
 * with a message about {@code bootstrap.servers} that says nothing about which switch was wrong.
 *
 * <p>Caught by the integration suite, which is the second time a Spring condition has been wrong in
 * this project in a way the annotation's name actively encouraged. The first was
 * {@code @ConditionalOnMissingBean} on a {@code @Component}; both are in the README's table.
 *
 * <p>Deleting the property from the YAML would also have worked and is worse: the env var is how a
 * deployment turns Kafka on, and a switch that only exists in a Java default is a switch nobody
 * finds.
 */
class KafkaConfigured implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        String brokers = context.getEnvironment().getProperty("till.kafka.bootstrap-servers", "");
        return !brokers.isBlank();
    }
}
