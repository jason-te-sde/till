package io.till.catalogue;

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
 * java -jar till-catalogue.jar \
 *     --spring.datasource.url=jdbc:postgresql://localhost:5432/catalogue \
 *     --catalogue.till.base-url=http://till:8080 \
 *     --catalogue.till.token=... \
 *     --catalogue.kafka.bootstrap-servers=kafka:9092
 * }</pre>
 */
@SpringBootApplication
@EnableConfigurationProperties(CatalogueProperties.class)
public class CatalogueApplication {

    /**
     * @param args Spring Boot arguments, including any {@code --catalogue.*} overrides
     */
    public static void main(String[] args) {
        SpringApplication.run(CatalogueApplication.class, args);
    }
}
