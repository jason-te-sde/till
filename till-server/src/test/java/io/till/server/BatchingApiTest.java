package io.till.server;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.micrometer.core.instrument.MeterRegistry;
import io.till.core.IdempotencyKey;
import io.till.core.Sku;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The service as configured by default runs its commands through the batcher (ADR 16).
 * {@link UnbatchedApiTest} is the same service with {@code till.batch.enabled} off.
 */
@ResourceLock("till-database")
class BatchingApiTest extends ApiTestBase {

    @Autowired
    MeterRegistry meters;

    /** See the same note in {@link BackgroundJobsApiTest}. */
    @BeforeEach
    void forgetEarlierTraffic() {
        meters.clear();
    }

    @Test
    @DisplayName("a command sent to the service is decided in a batch")
    void commandsAreBatched() {
        admin().adjust(IdempotencyKey.of("b1"), Sku.of("widget"), 10);

        assertEquals(1, meters.get("till.batch.size").summary().count());
        assertEquals(10, ledger.stock(Sku.of("widget")).orElseThrow().available());
    }
}
