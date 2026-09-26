package io.till.store.ledger;

import io.till.client.TillClient;
import io.till.store.StoreProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The two views of the ledger the store holds, each with its own credential. */
@Configuration
class LedgerConfiguration {

    /**
     * @param properties where the ledger is
     * @return the checkout path's view, with the client token
     */
    @Bean
    Holds holds(StoreProperties properties) {
        return new RemoteHolds(client(properties, properties.till().token()));
    }

    /**
     * @param properties where the ledger is
     * @return the operator console's view, with the admin token
     */
    @Bean
    OperatorLedger operatorLedger(StoreProperties properties) {
        return new RemoteOperatorLedger(client(properties, properties.till().adminToken()));
    }

    private static TillClient client(StoreProperties properties, String token) {
        StoreProperties.Till till = properties.till();
        return TillClient.builder(till.baseUrl())
                .token(token.isBlank() ? null : token)
                .timeout(till.timeout())
                .build();
    }
}
