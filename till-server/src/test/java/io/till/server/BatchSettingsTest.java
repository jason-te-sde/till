package io.till.server;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BatchSettingsTest {

    @Test
    @DisplayName("a batch and its queue each need room for at least one command")
    void sizesMustLeaveRoom() {
        assertThrows(IllegalArgumentException.class, () -> new TillProperties.Batch(true, 0, 1024, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> new TillProperties.Batch(true, 64, 0, Duration.ofSeconds(1)));
        assertDoesNotThrow(() -> new TillProperties.Batch(true, 1, 1, Duration.ofSeconds(1)));
    }

    @Test
    @DisplayName("a stall is longer than no time at all")
    void stallAfterMustBePositive() {
        assertThrows(IllegalArgumentException.class, () -> new TillProperties.Batch(true, 64, 1024, Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new TillProperties.Batch(true, 64, 1024, Duration.ofSeconds(-1)));
    }

    @Test
    @DisplayName("sizes are checked even with batching off, so turning it on cannot surprise anyone")
    void sizesAreCheckedWhenOff() {
        assertThrows(IllegalArgumentException.class, () -> new TillProperties.Batch(false, 0, 1024, Duration.ofSeconds(1)));
    }
}
