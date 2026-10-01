package io.till.store;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.time.Period;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link StoreProperties.Sales}'s validation: {@code retention} and {@code lockTimeout} are the two
 * settings here dangerous to get wrong, so they are the ones this class refuses rather than silently
 * accepts.
 */
class StorePropertiesTest {

    @Test
    @DisplayName("a retention shorter than the best-seller window is refused")
    void retentionMustCoverTheWindow() {
        assertThrows(IllegalArgumentException.class, () -> sales(Period.ofDays(3), Duration.ofSeconds(2)));
    }

    @Test
    @DisplayName("a retention of zero, or negative, is refused")
    void retentionMustBePositive() {
        assertThrows(IllegalArgumentException.class, () -> sales(Period.ZERO, Duration.ofSeconds(2)));
        assertThrows(IllegalArgumentException.class, () -> sales(Period.ofMonths(-1), Duration.ofSeconds(2)));
    }

    @Test
    @DisplayName("a lock timeout of zero, or negative, is refused")
    void lockTimeoutMustBePositive() {
        assertThrows(IllegalArgumentException.class, () -> sales(Period.ofMonths(13), Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> sales(Period.ofMonths(13), Duration.ofSeconds(-1)));
    }

    @Test
    @DisplayName("the defaults — thirteen months, a two-second lock timeout — are accepted")
    void defaultsAreAccepted() {
        assertDoesNotThrow(() -> sales(Period.ofMonths(13), Duration.ofSeconds(2)));
    }

    private static StoreProperties.Sales sales(Period retention, Duration lockTimeout) {
        return new StoreProperties.Sales(retention, lockTimeout);
    }
}
