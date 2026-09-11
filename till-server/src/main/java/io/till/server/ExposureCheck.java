package io.till.server;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Refuses to start an unauthenticated service on an address other people can reach.
 *
 * <p>An inventory ledger with no token is a service where anyone who can open a socket can reserve
 * every unit you have. The default configuration has no token in it, because a laptop should be one
 * command, so the two have to be reconciled somewhere: here, at startup, loudly.
 *
 * <p>{@code till.insecure=true} turns it off. It is named that way so that it cannot end up in a
 * production configuration without somebody having read it.
 */
@Component
class ExposureCheck {

    private static final Logger LOG = LoggerFactory.getLogger(ExposureCheck.class);

    private final TillProperties properties;
    private final String address;

    ExposureCheck(TillProperties properties, @Value("${server.address:}") String address) {
        this.properties = properties;
        this.address = address;
    }

    @PostConstruct
    void check() {
        if (properties.auth().isConfigured()) {
            if (!properties.auth().isAdminConfigured()) {
                LOG.warn(
                        "till.auth.admin-token is not set, so the client token also allows changing stock "
                                + "levels directly; set both to separate the two privileges");
            }
            return;
        }
        if (properties.insecure()) {
            LOG.warn("till.insecure=true: this service has no authentication and will accept any caller");
            return;
        }
        if (isLoopback()) {
            LOG.info("no till.auth.client-token set; listening on {} only", address.isBlank() ? "loopback" : address);
            return;
        }
        throw new IllegalStateException(
                "refusing to listen on " + describe() + " with no till.auth.client-token. "
                        + "Set a token, bind server.address to a loopback address, or pass till.insecure=true "
                        + "if you really mean to run an unauthenticated inventory ledger on a reachable address.");
    }

    private boolean isLoopback() {
        return "127.0.0.1".equals(address) || "::1".equals(address) || "localhost".equals(address);
    }

    private String describe() {
        return address.isBlank() ? "every interface (server.address is unset)" : address;
    }
}
