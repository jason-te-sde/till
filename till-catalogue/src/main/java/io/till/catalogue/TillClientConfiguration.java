package io.till.catalogue;

import io.till.client.TillClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The client this service reserves through. */
@Configuration
class TillClientConfiguration {

    /**
     * @param properties where till is and what token to use
     * @return the client
     */
    @Bean
    TillClient tillClient(CatalogueProperties properties) {
        CatalogueProperties.Till till = properties.till();
        return TillClient.builder(till.baseUrl())
                .token(till.token().isBlank() ? null : till.token())
                .timeout(till.timeout())
                .build();
    }
}
