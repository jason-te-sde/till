package io.till.store;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * The game store.
 *
 * <p>The half of the platform that faces customers, and deliberately the half that owns no stock. It
 * serves the catalogue from its own database, signs customers in, records their orders — and for every
 * decision about whether a sale may happen, asks the ledger over HTTP with an ordinary client token,
 * exactly as any other caller would. There is one place in the system where an oversell could be
 * decided, and it is not here.
 *
 * <p>It is also the browser's only backend. The SPA talks to this service and nothing else: sign-in
 * happens here, tokens stay here, and the operator console's requests are forwarded from here to the
 * ledger for users who are allowed to make them. The ledger itself is never exposed to a browser.
 *
 * <p>{@code @EnableScheduling} exists for {@code io.till.store.sales.SalesPartitionMaintenance}'s
 * daily run; without it, {@code @Scheduled} is a method a test can still call by hand, but nothing
 * Spring ever invokes on its own.
 */
@SpringBootApplication
@EnableConfigurationProperties(StoreProperties.class)
@EnableScheduling
public class StoreApplication {

    /**
     * @param args Spring Boot arguments, including any {@code --store.*} overrides
     */
    public static void main(String[] args) {
        SpringApplication.run(StoreApplication.class, args);
    }
}
