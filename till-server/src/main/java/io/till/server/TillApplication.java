package io.till.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * The till service.
 *
 * <p>A thin layer over {@code till-core}: parse a request, run one command, turn the outcome into a
 * status code. No reservation logic lives here, which is why the rules can be tested tens of
 * thousands of times a second without a container anywhere near them.
 *
 * <pre>{@code
 * java -jar till-server.jar \
 *     --spring.datasource.url=jdbc:postgresql://localhost:5432/till \
 *     --till.auth.client-token=... --till.auth.admin-token=...
 * }</pre>
 *
 * <p>The service refuses to listen on anything but loopback without tokens; see
 * {@link ExposureCheck}.
 */
@SpringBootApplication
@EnableConfigurationProperties(TillProperties.class)
@EnableScheduling
public class TillApplication {

    /**
     * @param args Spring Boot arguments, including any {@code --till.*} overrides
     */
    public static void main(String[] args) {
        SpringApplication.run(TillApplication.class, args);
    }
}
