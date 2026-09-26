package io.till.store;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * The storefront service.
 *
 * <p>Owns what a game is called and what it costs, and keeps a read model of what is still buyable
 * by consuming till's events. It owns <b>no stock</b>: every reservation goes to till over HTTP,
 * through the same published client anybody else would use, so this service has no privileged path
 * to the ledger and cannot become a second place where an oversell is decided.
 *
 * <pre>{@code
 * java -jar till-store.jar \
 *     --spring.datasource.url=jdbc:postgresql://localhost:5432/catalogue \
 *     --store.till.base-url=http://till:8080 \
 *     --store.till.token=... \
 *     --store.kafka.bootstrap-servers=kafka:9092
 * }</pre>
 */
@SpringBootApplication
@EnableConfigurationProperties(StoreProperties.class)
public class StoreApplication {

    /**
     * @param args Spring Boot arguments, including any {@code --store.*} overrides
     */
    public static void main(String[] args) {
        SpringApplication.run(StoreApplication.class, args);
    }
}
