package io.till.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link TestDatabase#cpuLimitNanos()} alone: the arithmetic a benchmark run's {@code -Dbench.dbcpus}
 * is turned into, without starting a container to check it. {@link ContentionBenchmark}'s javadoc is
 * what actually exercises the container-side half, a run at a time.
 */
class TestDatabaseTest {

    private static final String PROPERTY = "bench.dbcpus";

    @AfterEach
    void clearProperty() {
        System.clearProperty(PROPERTY);
    }

    @Test
    @DisplayName("no -Dbench.dbcpus means no limit")
    void unsetIsEmpty() {
        System.clearProperty(PROPERTY);

        assertTrue(TestDatabase.cpuLimitNanos().isEmpty());
    }

    @Test
    @DisplayName("-Dbench.dbcpus=2 is two billion nanoCPUs, Docker's unit for the limit")
    void convertsCpusToNanos() {
        System.setProperty(PROPERTY, "2");

        assertEquals(Optional.of(2_000_000_000L), TestDatabase.cpuLimitNanos());
    }
}
