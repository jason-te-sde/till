package io.till.core;

import static io.till.core.Fixtures.T0;
import static io.till.core.Fixtures.line;
import static io.till.core.Fixtures.sku;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The boundary: what the types refuse to be built out of. */
class ValueTypeTest {

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "-leading-dash", ".dot", "with space", "new\nline", "semi;colon", "quote'"})
    @DisplayName("a SKU that would need escaping somewhere is refused at the boundary")
    void refusesUnsafeSkus(String value) {
        assertThrows(IllegalArgumentException.class, () -> Sku.of(value));
    }

    @ParameterizedTest
    @ValueSource(strings = {"widget", "SKU-123", "sku_1.2", "urn:isbn:0451450523", "a", "9lives", "a+b", "a/b", "a=b"})
    @DisplayName("the identifiers people actually use are accepted")
    void acceptsOrdinarySkus(String value) {
        assertEquals(value, Sku.of(value).value());
    }

    @Test
    @DisplayName("a SKU that is too long is refused, because the column is not")
    void refusesOverlongSkus() {
        assertThrows(IllegalArgumentException.class, () -> Sku.of("s".repeat(Sku.MAX_LENGTH + 1)));
        assertEquals(Sku.MAX_LENGTH, Sku.of("s".repeat(Sku.MAX_LENGTH)).value().length());
    }

    @Test
    @DisplayName("a SKU prints as itself, because it is in every log line")
    void skuPrintsAsItself() {
        assertEquals("widget", sku("widget").toString());
    }

    @Test
    @DisplayName("a line of zero or fewer is refused")
    void refusesEmptyLines() {
        assertThrows(IllegalArgumentException.class, () -> Line.of("widget", 0));
        assertThrows(IllegalArgumentException.class, () -> Line.of("widget", -1));
        assertThrows(IllegalArgumentException.class, () -> Line.of("widget", Line.MAX_QUANTITY + 1));
    }

    @Test
    @DisplayName("a stock level with more reserved than on-hand cannot be built")
    void refusesImpossibleLevels() {
        IllegalArgumentException thrown =
                assertThrows(IllegalArgumentException.class, () -> new StockItem(sku("widget"), 3, 4, 0));

        assertTrue(thrown.getMessage().contains("reserved"), thrown.getMessage());
    }

    @Test
    @DisplayName("available is on-hand minus reserved")
    void availableIsTheDifference() {
        assertEquals(3, new StockItem(sku("widget"), 10, 7, 0).available());
        assertEquals(0, new StockItem(sku("widget"), 7, 7, 0).available());
    }

    @Test
    @DisplayName("a SKU with no row is not the same as a SKU with nothing in it")
    void absenceIsNotEmptiness() {
        assertTrue(StockItem.empty(sku("ghost")).exists() == false);
        assertTrue(new StockItem(sku("widget"), 0, 0, 0).exists());
    }

    @Test
    @DisplayName("the same SKU twice in one reservation is refused, not merged")
    void refusesDuplicateSkus() {
        IllegalArgumentException thrown =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> Reservation.canonical(List.of(line("widget", 1), line("widget", 2))));

        assertTrue(thrown.getMessage().contains("widget"), thrown.getMessage());
    }

    @Test
    @DisplayName("a reservation with no lines is refused")
    void refusesEmptyReservations() {
        assertThrows(IllegalArgumentException.class, () -> Reservation.canonical(List.of()));
    }

    @Test
    @DisplayName("a time to live beyond the maximum is refused")
    void refusesAbsurdTtls() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new Command.Reserve(
                                Fixtures.key("k"), Fixtures.rid("r"), List.of(line("widget", 1)), Duration.ofDays(31)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new Command.Reserve(Fixtures.key("k"), Fixtures.rid("r"), List.of(line("widget", 1)), Duration.ZERO));
    }

    @Test
    @DisplayName("a reservation that expires before it was created cannot be built")
    void refusesBackwardsDeadlines() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new Reservation(
                                Fixtures.rid("r"),
                                Fixtures.key("k"),
                                List.of(line("widget", 1)),
                                ReservationState.HELD,
                                T0,
                                T0.minusSeconds(1),
                                0));
    }

    @Test
    @DisplayName("a reservation only ever moves to a terminal state")
    void refusesAMutationBackToHeld() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new Mutation.SetReservationState(Fixtures.rid("r"), ReservationState.HELD, 0));
    }
}
