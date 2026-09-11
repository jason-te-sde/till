package io.till.server;

import io.till.core.Codec;
import io.till.core.OutboxEntry;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
/**
 * The default destination: a log line per event.
 *
 * <p>Not a placeholder. A service whose events go to a log is a service whose events can be grepped,
 * shipped by whatever is already shipping its logs, and replayed from an archive — which is more than
 * many deployments need from an outbox. It is also what makes the publisher's own behaviour visible
 * in an integration test without standing up a broker.
 *
 * <p>Not a bean. {@link OutboxPublisher} asks for an {@link EventPublisher} and falls back to this
 * when there is none, which is a deliberate choice over {@code @ConditionalOnMissingBean}: that
 * annotation is only defined to work on a {@code @Bean} method inside an auto-configuration, and on
 * a {@code @Component} it is evaluated against whatever has been scanned so far. Used that way here
 * it silently produced no publisher at all, and the service refused to start — which the integration
 * suite could not see, because it supplies a publisher of its own.
 *
 * <p>Replace it by defining a bean of type {@link EventPublisher}; this one steps aside.
 */
final class LoggingEventPublisher implements EventPublisher {

    private static final Logger LOG = LoggerFactory.getLogger("io.till.events");

    @Override
    public void publish(List<OutboxEntry> entries) {
        for (OutboxEntry entry : entries) {
            LOG.info("{} {} {}", entry.sequence(), entry.dedupeKey(), Codec.encodeEvent(entry.event()));
        }
    }
}
