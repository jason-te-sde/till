package io.till.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The client's JSON.
 *
 * <p>A hand-written parser earns its place only if it is strict, so most of these are about what it
 * refuses. A lenient parser inside a client turns a service that has started sending something
 * unexpected into a client that quietly reads the wrong field.
 */
class JsonTest {

    @Test
    @DisplayName("an object of the shape this API returns")
    void parsesAnObject() {
        Object parsed =
                Json.parse(
                        """
                        {"id":"r-1","lines":[{"sku":"widget","quantity":2}],
                         "expiresAt":"2026-09-10T12:15:00Z","ok":true,"nothing":null}""");

        Map<?, ?> object = assertInstanceOf(Map.class, parsed);
        assertEquals("r-1", object.get("id"));
        assertEquals(Boolean.TRUE, object.get("ok"));
        assertNull(object.get("nothing"));
        List<?> lines = assertInstanceOf(List.class, object.get("lines"));
        assertEquals(2L, ((Map<?, ?>) lines.get(0)).get("quantity"));
    }

    @Test
    @DisplayName("a whole number comes back exact, not as a double")
    void integersStayExact() {
        assertEquals(9007199254740993L, Json.parse("9007199254740993"));
        assertEquals(-42L, Json.parse("-42"));
        assertInstanceOf(Double.class, Json.parse("1.5"));
        assertInstanceOf(Double.class, Json.parse("1e3"));
    }

    @Test
    @DisplayName("escapes are read and written")
    void escapes() {
        String awkward = "quote \" backslash \\ newline \n tab \t control \u0001 unicode \u00e9";

        assertEquals(awkward, Json.parse(Json.write(awkward)));
        assertEquals("\u00e9", Json.parse("\"\\u00e9\""));
        assertEquals("a/b", Json.parse("\"a\\/b\""));
    }

    @Test
    @DisplayName("a document written by the writer is read by the reader")
    void roundTrips() {
        Object value =
                Map.of("lines", List.of(Map.of("sku", "widget", "quantity", 2L)), "ttlSeconds", 900L);

        assertEquals(value, Json.parse(Json.write(value)));
    }

    @Test
    @DisplayName("empty containers")
    void emptyContainers() {
        assertEquals(Map.of(), Json.parse("{}"));
        assertEquals(List.of(), Json.parse("[]"));
        assertEquals(Map.of(), Json.parse("  {  }  "));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{\"a\":1,}",
                "[1,2,]",
                "{a:1}",
                "{'a':1}",
                "{\"a\":1} trailing",
                "NaN",
                "\"unterminated",
                "\"bad \\x escape\"",
                "\"raw \u0001 control\"",
                "\"\\u00zz\"",
                "tru",
                "-",
                "",
                "   ",
                "{\"a\"}",
                "{\"a\":}"
            })
    @DisplayName("anything that is not JSON is refused rather than guessed at")
    void refusesMalformed(String text) {
        assertThrows(IllegalArgumentException.class, () -> Json.parse(text));
    }

    @Test
    @DisplayName("a type the writer cannot represent is refused")
    void refusesUnwritableTypes() {
        assertThrows(IllegalArgumentException.class, () -> Json.write(new Object()));
        assertThrows(IllegalArgumentException.class, () -> Json.write(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> Json.write(Double.POSITIVE_INFINITY));
    }

    @Test
    @DisplayName("a failure says where it happened")
    void failuresNameTheOffset() {
        IllegalArgumentException thrown =
                assertThrows(IllegalArgumentException.class, () -> Json.parse("{\"a\":1,}"));

        assertEquals(true, thrown.getMessage().contains("offset"), thrown.getMessage());
    }
}
