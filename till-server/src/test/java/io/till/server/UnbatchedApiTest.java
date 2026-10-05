package io.till.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.micrometer.core.instrument.MeterRegistry;
import io.till.core.IdempotencyKey;
import io.till.core.Sku;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/** With {@code till.batch.enabled} off, each command runs on its own, as before ADR 16. */
@ResourceLock("till-database")
@TestPropertySource(properties = "till.batch.enabled=false")
class UnbatchedApiTest extends ApiTestBase {

    @Autowired
    MeterRegistry meters;

    @Autowired
    ObjectProvider<CommandBatcher> batcher;

    @Test
    @DisplayName("with batching off there is no batcher, and a command still runs")
    void commandsRunOnTheirOwn() {
        meters.clear();

        admin().adjust(IdempotencyKey.of("u1"), Sku.of("widget"), 10);

        assertNull(batcher.getIfAvailable());
        assertNull(meters.find("till.batch.size").summary());
        assertEquals(10, ledger.stock(Sku.of("widget")).orElseThrow().available());
    }
}
