package io.till.server;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BatchSettingsTest {

    @Test
    @DisplayName("a batch and its queue each need room for at least one command")
    void sizesMustLeaveRoom() {
        assertThrows(IllegalArgumentException.class, () -> new TillProperties.Batch(true, 0, 1024));
        assertThrows(IllegalArgumentException.class, () -> new TillProperties.Batch(true, 64, 0));
        assertDoesNotThrow(() -> new TillProperties.Batch(true, 1, 1));
    }

    @Test
    @DisplayName("sizes are checked even with batching off, so turning it on cannot surprise anyone")
    void sizesAreCheckedWhenOff() {
        assertThrows(IllegalArgumentException.class, () -> new TillProperties.Batch(false, 0, 1024));
    }
}
