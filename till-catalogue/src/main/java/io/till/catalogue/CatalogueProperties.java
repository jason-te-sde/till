package io.till.catalogue;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Everything this service needs pointing at.
 *
 * @param till where the ledger is, and the token to talk to it with
 * @param kafka where the events come from
 */
@ConfigurationProperties(prefix = "catalogue")
public record CatalogueProperties(@DefaultValue Till till, @DefaultValue Kafka kafka) {

    /**
     * @param baseUrl the till service
     * @param token the client token; the storefront never needs the admin one, because it only ever
     *     reserves, commits and releases — moving stock levels is not a thing a shop front does
     * @param timeout per-request timeout
     */
    public record Till(
            @DefaultValue("http://127.0.0.1:8080") String baseUrl,
            @DefaultValue("") String token,
            @DefaultValue("5s") Duration timeout) {}

    /**
     * @param bootstrapServers the broker list; blank means do not consume at all, and availability
     *     stays at whatever was last known
     * @param topic the topic till publishes to
     * @param groupId the consumer group; two instances of this service share one, so each event is
     *     projected once rather than once per instance
     * @param pollTimeout how long a poll waits before coming back empty
     */
    public record Kafka(
            @DefaultValue("") String bootstrapServers,
            @DefaultValue("till.events") String topic,
            @DefaultValue("till-catalogue") String groupId,
            @DefaultValue("500ms") Duration pollTimeout) {

        /**
         * @return whether a broker was configured
         */
        public boolean isConfigured() {
            return !bootstrapServers.isBlank();
        }
    }
}
