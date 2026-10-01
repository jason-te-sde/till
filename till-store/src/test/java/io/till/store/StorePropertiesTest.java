package io.till.store;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Period;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link StoreProperties.Sales}'s validation: the one setting here that is dangerous to get wrong is
 * {@code retention}, so it is the one this class refuses rather than silently accepts.
 */
class StorePropertiesTest {

    @Test
    @DisplayName("a retention shorter than the best-seller window is refused")
    void retentionMustCoverTheWindow() {
        assertThrows(IllegalArgumentException.class, () -> sales(Period.ofDays(3)));
    }

    @Test
    @DisplayName("a retention of zero, or negative, is refused")
    void retentionMustBePositive() {
        assertThrows(IllegalArgumentException.class, () -> sales(Period.ZERO));
        assertThrows(IllegalArgumentException.class, () -> sales(Period.ofMonths(-1)));
    }

    @Test
    @DisplayName("the default, thirteen months, comfortably covers the window")
    void defaultIsAccepted() {
        assertDoesNotThrow(() -> sales(Period.ofMonths(13)));
    }

    private static StoreProperties.Sales sales(Period retention) {
        return new StoreProperties.Sales(retention);
    }
}
