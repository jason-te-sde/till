package io.till.core;

import static io.till.core.Fixtures.T0;
import static io.till.core.Fixtures.key;
import static io.till.core.Fixtures.line;
import static io.till.core.Fixtures.rid;
import static io.till.core.Fixtures.sku;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The format a recorded outcome is replayed from.
 *
 * <p>A round trip that loses a field does not fail here — it fails months later, as a customer being
 * told about a reservation that expires at the wrong time.
 */
class CodecTest {

    static Stream<Outcome> outcomes() {
        return Stream.of(
                new Outcome.Reserved(rid("r1"), List.of(line("widget", 2), line("gadget", 1)), T0.plus(Duration.ofMinutes(15))),
                new Outcome.Committed(rid("r1"), T0),
                new Outcome.Released(rid("r1"), T0),
                new Outcome.Adjusted(sku("widget"), 100, 7),
                new Outcome.Swept(42),
                Outcome.Rejected.of(RejectionCode.RESERVATION_NOT_FOUND, "no reservation r9"),
                new Outcome.Rejected(
                        RejectionCode.INSUFFICIENT_STOCK,
                        "not enough stock for widget (wanted 5, have 2)",
                        List.of(new Outcome.Shortfall(sku("widget"), 5, 2), new Outcome.Shortfall(sku("gadget"), 1, 0))));
    }

    @ParameterizedTest
    @MethodSource("outcomes")
    @DisplayName("every outcome survives a round trip")
    void outcomesRoundTrip(Outcome outcome) {
        String encoded = Codec.encodeOutcome(outcome);

        assertEquals(outcome, Codec.decodeOutcome(encoded));
        assertTrue(encoded.startsWith("v1 "), encoded);
    }

    static Stream<Event> events() {
        return Stream.of(
                new Event.StockReserved(rid("r1"), List.of(line("widget", 2)), T0.plus(Duration.ofMinutes(15)), T0),
                new Event.StockCommitted(rid("r1"), List.of(line("widget", 2), line("gadget", 3)), T0),
                new Event.StockReleased(rid("r1"), List.of(line("widget", 2)), T0),
                new Event.StockExpired(rid("r1"), List.of(line("widget", 2)), T0),
                new Event.StockAdjusted(key("delivery-41"), sku("widget"), -5, 95, 7, T0));
    }

    @ParameterizedTest
    @MethodSource("events")
    @DisplayName("every event survives a round trip")
    void eventsRoundTrip(Event event) {
        assertEquals(event, Codec.decodeEvent(Codec.encodeEvent(event)));
    }

    @Test
    @DisplayName("an instant keeps its precision, which epoch milliseconds would not")
    void instantsKeepTheirPrecision() {
        java.time.Instant precise = java.time.Instant.parse("2026-09-10T12:00:00.123456Z");
        Outcome outcome = new Outcome.Committed(rid("r1"), precise);

        assertEquals(precise, ((Outcome.Committed) Codec.decodeOutcome(Codec.encodeOutcome(outcome))).at());
    }

    @Test
    @DisplayName("free text with spaces and newlines survives the token format")
    void detailIsEscaped() {
        Outcome outcome =
                Outcome.Rejected.of(RejectionCode.UNKNOWN_SKU, "a detail with spaces\nnewlines\tand a \\ backslash");

        String encoded = Codec.encodeOutcome(outcome);
        assertEquals(1, encoded.lines().count(), "an encoded record is one line: " + encoded);
        assertEquals(outcome, Codec.decodeOutcome(encoded));
    }

    @Test
    @DisplayName("escaping survives arbitrary text")
    void escapingIsReversible() {
        Random random = new Random(8123);
        String alphabet = " \\\n\r\tabc=:,";
        for (int i = 0; i < 2_000; i++) {
            StringBuilder sb = new StringBuilder();
            for (int j = 0; j < random.nextInt(24); j++) {
                sb.append(alphabet.charAt(random.nextInt(alphabet.length())));
            }
            String raw = sb.toString();
            assertEquals(raw, Codec.unescape(Codec.escape(raw)));
        }
    }

    @Test
    @DisplayName("an empty rejection detail is still a valid record")
    void emptyDetail() {
        Outcome outcome = Outcome.Rejected.of(RejectionCode.UNKNOWN_SKU, "");

        assertEquals(outcome, Codec.decodeOutcome(Codec.encodeOutcome(outcome)));
    }

    @Test
    @DisplayName("a format this build does not know is refused rather than guessed at")
    void unknownVersionIsRefused() {
        IllegalArgumentException thrown =
                assertThrows(IllegalArgumentException.class, () -> Codec.decodeOutcome("v2 committed id=r1 at=" + T0));

        assertTrue(thrown.getMessage().contains("v2"), thrown.getMessage());
    }

    @Test
    @DisplayName("a missing field is named")
    void missingFieldIsNamed() {
        IllegalArgumentException thrown =
                assertThrows(IllegalArgumentException.class, () -> Codec.decodeOutcome("v1 committed id=r1"));

        assertTrue(thrown.getMessage().contains("at"), thrown.getMessage());
    }

    @Test
    @DisplayName("an unknown kind is refused")
    void unknownKindIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> Codec.decodeOutcome("v1 teleported id=r1"));
    }

    @Test
    @DisplayName("a dangling escape is refused rather than silently dropped")
    void danglingEscapeIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> Codec.unescape("abc\\"));
    }
}
