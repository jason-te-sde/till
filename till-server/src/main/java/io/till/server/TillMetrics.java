package io.till.server;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.till.jdbc.JdbcLedger;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The meters that are not attached to a request.
 *
 * <p>The outbox backlog lives here rather than in {@link OutboxPublisher}, and that is the whole
 * point of the file. It used to be a number the publisher pushed after each run, which meant it was
 * accurate exactly when the publisher was working and frozen at its last value when it was not — so
 * the alert in the operations guide, "the backlog is growing", could never fire for the failure it
 * was written for. A metric that reports on a component must not be kept alive by that component.
 *
 * <p>Now it is read through to the database when Prometheus scrapes. That is one indexed
 * {@code count(*)} against a partial index per scrape, which at any sane scrape interval is free,
 * and it is correct whether the publisher is running, disabled, or dead.
 */
@Configuration
class TillMetrics {

    /**
     * @param ledger the outbox to count
     * @param registry where the gauge goes
     * @return the gauge, registered
     */
    @Bean
    Gauge outboxBacklog(JdbcLedger ledger, MeterRegistry registry) {
        return Gauge.builder("till.outbox.backlog", ledger, JdbcLedger::backlog)
                .description("Events written but not yet published. The number to alert on: it "
                        + "growing means everything downstream is working from a picture of stock "
                        + "that is falling further behind.")
                .strongReference(true)
                .register(registry);
    }
}
