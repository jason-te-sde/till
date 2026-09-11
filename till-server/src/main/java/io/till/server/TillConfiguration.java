package io.till.server;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import java.util.List;
import io.till.core.Till;
import io.till.jdbc.JdbcLedger;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the kernel to the database. */
@Configuration
class TillConfiguration {

    /**
     * One bean serves three ports: the ledger, the read-only view behind the GET endpoints, and the
     * outbox the publisher drains. They are separate interfaces because they have separate callers
     * with separate costs, not because they need separate implementations.
     *
     * @param dataSource the pool Spring Boot configured
     * @return the ledger
     */
    @Bean
    JdbcLedger ledger(DataSource dataSource) {
        return new JdbcLedger(dataSource);
    }

    /**
     * @param ledger where the rows live
     * @param clock the clock the service reads, once per attempt
     * @param properties the configured limits
     * @return the till
     */
    @Bean
    Till till(JdbcLedger ledger, Clock clock, TillProperties properties) {
        return Till.builder(ledger)
                .clock(clock)
                .maxAttempts(properties.maxAttempts())
                .reclaimLimit(properties.reclaimLimit())
                .build();
    }

    /**
     * A bean rather than a call to {@code Instant.now()}, so that a test can fix time and a hold's
     * deadline can be asserted exactly instead of approximately.
     *
     * @return the system clock, in UTC
     */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * @return the OpenAPI document's metadata and its bearer scheme
     */
    @Bean
    OpenAPI openApi() {
        return new OpenAPI()
                // A relative URL, and set explicitly. Left alone, springdoc derives an absolute one
                // from whichever request it happened to answer — which behind a proxy is the proxy's
                // view of itself rather than a URL any client can use, and which changes on every run
                // when the port is random. OpenApiContractTest is what noticed.
                .servers(List.of(new Server().url("/").description("this service")))
                .info(
                        new Info()
                                .title("till")
                                .version("0.1.0")
                                .description(
                                        "An oversell-proof inventory reservation service. Every mutating "
                                                + "endpoint requires an Idempotency-Key header; sending the same key "
                                                + "twice returns the first answer and changes nothing.")
                                .license(new License().name("MIT").url("https://opensource.org/licenses/MIT")))
                .components(
                        new Components()
                                .addSecuritySchemes(
                                        "bearer",
                                        new SecurityScheme()
                                                .type(SecurityScheme.Type.HTTP)
                                                .scheme("bearer")
                                                .description(
                                                        "till.auth.client-token for everything, "
                                                                + "till.auth.admin-token for changing stock levels")))
                .addSecurityItem(new SecurityRequirement().addList("bearer"));
    }
}
