package io.till.catalogue;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * True only when a broker list has actually been given.
 *
 * <p>Same trap as on the producing side, same fix: {@code @ConditionalOnProperty} asks whether the
 * property is present, and a YAML default of {@code ${CATALOGUE_KAFKA_BROKERS:}} is present and
 * empty on every deployment that has not opted in.
 *
 * <p>Without a broker the catalogue still serves: prices and titles come from its own tables, and
 * availability is whatever was last projected. That is the right degradation for a storefront — a
 * broker outage should make the numbers stale, not the shop unreachable.
 */
class KafkaConfigured implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        return !context.getEnvironment()
                .getProperty("catalogue.kafka.bootstrap-servers", "")
                .isBlank();
    }
}
