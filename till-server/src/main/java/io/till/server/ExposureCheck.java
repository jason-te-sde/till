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

    /** What Spring Boot reads as "do not start a management server at all". */
    private static final String MANAGEMENT_DISABLED = "-1";

    private final TillProperties properties;
    private final String address;
    private final String serverPort;
    private final String managementPort;

    ExposureCheck(
            TillProperties properties,
            @Value("${server.address:}") String address,
            @Value("${server.port:8080}") String serverPort,
            @Value("${management.server.port:}") String managementPort) {
        this.properties = properties;
        this.address = address;
        this.serverPort = serverPort;
        this.managementPort = managementPort;
    }

    @PostConstruct
    void check() {
        warnIfManagementSharesThePort();
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

    /**
     * Says so when health and metrics are on the same port as the API.
     *
     * <p>The authentication filter covers {@code /v1} and nothing else, because in the shipped
     * configuration the management endpoints are on a port of their own and are not reachable from
     * where the API is. Unset that port — or set it to the API's — and {@code /actuator/prometheus}
     * becomes readable by anyone who can reach the service, with no token.
     *
     * <p>A warning rather than a refusal: it is the right configuration for a test, and the tests in
     * this repository use it.
     */
    private void warnIfManagementSharesThePort() {
        if (MANAGEMENT_DISABLED.equals(managementPort)) {
            return;
        }
        // Blank counts as shared: unset, Spring Boot serves the management endpoints from the
        // application's own port rather than from none.
        if (managementPort.isBlank() || managementPort.equals(serverPort)) {
            LOG.warn(
                    "management endpoints are on the same port as the API ({}), so /actuator is "
                            + "reachable without a token; set management.server.port to separate them",
                    serverPort);
        }
    }

    private boolean isLoopback() {
        return "127.0.0.1".equals(address) || "::1".equals(address) || "localhost".equals(address);
    }

    private String describe() {
        return address.isBlank() ? "every interface (server.address is unset)" : address;
    }
}
