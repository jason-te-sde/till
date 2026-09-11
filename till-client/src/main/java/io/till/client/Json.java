package io.till.client;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Just enough JSON.
 *
 * <p>A client library is a dependency of somebody else's service, and the most common way one causes
 * trouble is by disagreeing with that service about the version of a serialisation library. So this
 * module has none: the reader below handles RFC 8259 and the writer produces the four request shapes
 * this API accepts.
 *
 * <p>It is deliberately strict. Trailing commas, unquoted keys, single quotes, {@code NaN}, comments
 * and trailing content after the top-level value are all refused rather than tolerated, because a
 * lenient parser in a client turns a server that has started sending something unexpected into a
 * client that quietly reads the wrong field.
 *
 * <p>Numbers come back as {@link Long} when they have no fraction or exponent and {@link Double}
 * otherwise. Every number this API returns is a count or a version, so the common case is exact and
 * the other case is there for completeness rather than for use.
 */
final class Json {

    private Json() {}

    /**
     * Parses one JSON document.
     *
     * @param text the document
     * @return a {@code Map<String, Object>}, {@code List<Object>}, {@link String}, {@link Long},
     *     {@link Double}, {@link Boolean}, or null
     * @throws IllegalArgumentException on anything that is not valid JSON, or on trailing content
     */
    static Object parse(String text) {
        Parser parser = new Parser(text);
        parser.skipWhitespace();
        Object value = parser.value();
        parser.skipWhitespace();
        if (!parser.atEnd()) {
            throw parser.fail("trailing content after the top-level value");
        }
        return value;
    }

    /**
     * Writes a value.
     *
     * @param value a Map, List, String, Number, Boolean, or null
     * @return the JSON form, with no insignificant whitespace
     * @throws IllegalArgumentException on a type this writer does not handle
     */
    static String write(Object value) {
        StringBuilder sb = new StringBuilder(64);
        writeTo(sb, value);
        return sb.toString();
    }

    private static void writeTo(StringBuilder sb, Object value) {
        switch (value) {
            case null -> sb.append("null");
            case String s -> writeString(sb, s);
            case Boolean b -> sb.append(b);
            case Long l -> sb.append(l.longValue());
            case Integer i -> sb.append(i.intValue());
            case Double d -> {
                if (d.isNaN() || d.isInfinite()) {
                    throw new IllegalArgumentException("JSON has no representation for " + d);
                }
                sb.append(d.doubleValue());
            }
            case Map<?, ?> map -> {
                sb.append('{');
                boolean first = true;
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (!first) {
                        sb.append(',');
                    }
                    first = false;
                    writeString(sb, String.valueOf(entry.getKey()));
                    sb.append(':');
                    writeTo(sb, entry.getValue());
                }
                sb.append('}');
            }
            case List<?> list -> {
                sb.append('[');
                for (int i = 0; i < list.size(); i++) {
                    if (i > 0) {
                        sb.append(',');
                    }
                    writeTo(sb, list.get(i));
                }
                sb.append(']');
            }
            default -> throw new IllegalArgumentException("cannot write " + value.getClass() + " as JSON");
        }
    }

    private static void writeString(StringBuilder sb, String value) {
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    /** Reads a document one character at a time. */
    private static final class Parser {

        private final String text;
        private int at;

        private Parser(String text) {
            if (text == null) {
                throw new IllegalArgumentException("nothing to parse");
            }
            this.text = text;
        }

        private boolean atEnd() {
            return at >= text.length();
        }

        private void skipWhitespace() {
            while (at < text.length()) {
                char c = text.charAt(at);
                if (c != ' ' && c != '\t' && c != '\n' && c != '\r') {
                    return;
                }
                at++;
            }
        }

        private Object value() {
            if (atEnd()) {
                throw fail("expected a value");
            }
            char c = text.charAt(at);
            return switch (c) {
                case '{' -> object();
                case '[' -> array();
                case '"' -> string();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", null);
                default -> number();
            };
        }

        private Map<String, Object> object() {
            expect('{');
            Map<String, Object> map = new LinkedHashMap<>();
            skipWhitespace();
            if (peek() == '}') {
                at++;
                return map;
            }
            while (true) {
                skipWhitespace();
                if (peek() != '"') {
                    throw fail("an object key must be a quoted string");
                }
                String key = string();
                skipWhitespace();
                expect(':');
                skipWhitespace();
                map.put(key, value());
                skipWhitespace();
                char next = peek();
                at++;
                if (next == '}') {
                    return map;
                }
                if (next != ',') {
                    throw fail("expected ',' or '}' in an object");
                }
            }
        }

        private List<Object> array() {
            expect('[');
            List<Object> list = new ArrayList<>();
            skipWhitespace();
            if (peek() == ']') {
                at++;
                return list;
            }
            while (true) {
                skipWhitespace();
                list.add(value());
                skipWhitespace();
                char next = peek();
                at++;
                if (next == ']') {
                    return list;
                }
                if (next != ',') {
                    throw fail("expected ',' or ']' in an array");
                }
            }
        }

        private String string() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (atEnd()) {
                    throw fail("a string was never closed");
                }
                char c = text.charAt(at++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c != '\\') {
                    if (c < 0x20) {
                        throw fail("a raw control character in a string must be escaped");
                    }
                    sb.append(c);
                    continue;
                }
                if (atEnd()) {
                    throw fail("a string ended in an escape");
                }
                char escape = text.charAt(at++);
                switch (escape) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/' -> sb.append('/');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'u' -> sb.append(unicode());
                    default -> throw fail("unknown escape '\\" + escape + "'");
                }
            }
        }

        private char unicode() {
            if (at + 4 > text.length()) {
                throw fail("a \\u escape needs four hex digits");
            }
            String hex = text.substring(at, at + 4);
            at += 4;
            try {
                return (char) Integer.parseInt(hex, 16);
            } catch (NumberFormatException e) {
                throw fail("'" + hex + "' is not four hex digits");
            }
        }

        private Object literal(String expected, Object result) {
            if (!text.startsWith(expected, at)) {
                throw fail("expected " + expected);
            }
            at += expected.length();
            return result;
        }

        private Object number() {
            int start = at;
            if (peek() == '-') {
                at++;
            }
            boolean exact = true;
            while (!atEnd()) {
                char c = text.charAt(at);
                if (c >= '0' && c <= '9') {
                    at++;
                } else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                    exact = false;
                    at++;
                } else {
                    break;
                }
            }
            String token = text.substring(start, at);
            if (token.isEmpty() || "-".equals(token)) {
                throw fail("expected a number");
            }
            try {
                return exact ? (Object) Long.valueOf(token) : (Object) Double.valueOf(token);
            } catch (NumberFormatException e) {
                throw fail("'" + token + "' is not a number");
            }
        }

        private char peek() {
            if (atEnd()) {
                throw fail("the document ended early");
            }
            return text.charAt(at);
        }

        private void expect(char c) {
            if (atEnd() || text.charAt(at) != c) {
                throw fail("expected '" + c + "'");
            }
            at++;
        }

        private IllegalArgumentException fail(String message) {
            return new IllegalArgumentException(message + " at offset " + at + " of: " + preview());
        }

        private String preview() {
            return text.length() <= 200 ? text : text.substring(0, 200) + "...";
        }
    }
}
