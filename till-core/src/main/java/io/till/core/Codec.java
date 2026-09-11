package io.till.core;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The text form of outcomes and events.
 *
 * <p>Two things need a stored representation and neither can use a JSON library: a recorded outcome,
 * which is replayed to a caller months later and must come back as the same values, and an outbox
 * row, which is read by a publisher that may be a different version of this code. Pulling in a
 * serialisation framework for that would put a dependency — and its upgrade treadmill — into a module
 * whose whole claim is that it has almost none.
 *
 * <p>The format is one line of {@code name=value} tokens after a version tag:
 *
 * <pre>
 *   v1 reserved id=r-7 expires=2026-09-10T12:00:00Z lines=widget:2,gadget:1
 *   v1 rejected code=INSUFFICIENT_STOCK short=widget:5:2 detail=widget\sis\sshort\sby\s3
 * </pre>
 *
 * <p>Values are unescaped except for the free-text {@code detail}, and identifiers cannot contain a
 * space or an equals sign by construction — see {@link Ids} — so a token never needs quoting. The
 * leading {@code v1} is what lets a later format be introduced without a migration: a reader that
 * does not know a tag says so instead of guessing.
 *
 * <p>Instants are ISO-8601 as {@link Instant#toString()} writes them, which round-trips exactly,
 * rather than epoch milliseconds, which does not.
 */
public final class Codec {

    /** The only format version this build writes. */
    static final String VERSION = "v1";

    private Codec() {}

    /**
     * Writes an outcome.
     *
     * @param outcome what happened
     * @return one line of text
     */
    public static String encodeOutcome(Outcome outcome) {
        return switch (outcome) {
            case Outcome.Reserved r ->
                    head("reserved")
                            .put("id", r.id().value())
                            .put("expires", r.expiresAt().toString())
                            .put("lines", encodeLines(r.lines()))
                            .done();
            case Outcome.Committed c ->
                    head("committed").put("id", c.id().value()).put("at", c.at().toString()).done();
            case Outcome.Released r ->
                    head("released").put("id", r.id().value()).put("at", r.at().toString()).done();
            case Outcome.Adjusted a ->
                    head("adjusted")
                            .put("sku", a.sku().value())
                            .put("onhand", Long.toString(a.onHand()))
                            .put("reserved", Long.toString(a.reserved()))
                            .done();
            case Outcome.Swept s -> head("swept").put("n", Integer.toString(s.reclaimed())).done();
            case Outcome.Rejected r ->
                    head("rejected")
                            .put("code", r.code().name())
                            .put("short", encodeShortfalls(r.shortfalls()))
                            .put("detail", escape(r.detail()))
                            .done();
        };
    }

    /**
     * Reads an outcome back.
     *
     * @param text a line written by {@link #encodeOutcome}
     * @return the outcome
     * @throws IllegalArgumentException if the text is not a form this build understands
     */
    public static Outcome decodeOutcome(String text) {
        Frame frame = Frame.parse(text);
        return switch (frame.kind()) {
            case "reserved" ->
                    new Outcome.Reserved(
                            ReservationId.of(frame.get("id")),
                            decodeLines(frame.get("lines")),
                            Instant.parse(frame.get("expires")));
            case "committed" ->
                    new Outcome.Committed(ReservationId.of(frame.get("id")), Instant.parse(frame.get("at")));
            case "released" ->
                    new Outcome.Released(ReservationId.of(frame.get("id")), Instant.parse(frame.get("at")));
            case "adjusted" ->
                    new Outcome.Adjusted(
                            Sku.of(frame.get("sku")), frame.getLong("onhand"), frame.getLong("reserved"));
            case "swept" -> new Outcome.Swept((int) frame.getLong("n"));
            case "rejected" ->
                    new Outcome.Rejected(
                            RejectionCode.valueOf(frame.get("code")),
                            unescape(frame.get("detail")),
                            decodeShortfalls(frame.get("short")));
            default -> throw new IllegalArgumentException("unknown outcome kind '" + frame.kind() + "' in: " + text);
        };
    }

    /**
     * Writes an event.
     *
     * @param event what happened
     * @return one line of text
     */
    public static String encodeEvent(Event event) {
        return switch (event) {
            case Event.StockReserved e ->
                    head("reserved")
                            .put("id", e.reservationId().value())
                            .put("expires", e.expiresAt().toString())
                            .put("at", e.occurredAt().toString())
                            .put("lines", encodeLines(e.lines()))
                            .done();
            case Event.StockCommitted e -> movement("committed", e.reservationId(), e.lines(), e.occurredAt());
            case Event.StockReleased e -> movement("released", e.reservationId(), e.lines(), e.occurredAt());
            case Event.StockExpired e -> movement("expired", e.reservationId(), e.lines(), e.occurredAt());
            case Event.StockAdjusted e ->
                    head("adjusted")
                            .put("key", e.key().value())
                            .put("sku", e.sku().value())
                            .put("delta", Long.toString(e.delta()))
                            .put("onhand", Long.toString(e.onHand()))
                            .put("reserved", Long.toString(e.reserved()))
                            .put("at", e.occurredAt().toString())
                            .done();
        };
    }

    /**
     * Reads an event back.
     *
     * @param text a line written by {@link #encodeEvent}
     * @return the event
     * @throws IllegalArgumentException if the text is not a form this build understands
     */
    public static Event decodeEvent(String text) {
        Frame frame = Frame.parse(text);
        return switch (frame.kind()) {
            case "reserved" ->
                    new Event.StockReserved(
                            ReservationId.of(frame.get("id")),
                            decodeLines(frame.get("lines")),
                            Instant.parse(frame.get("expires")),
                            Instant.parse(frame.get("at")));
            case "committed" ->
                    new Event.StockCommitted(
                            ReservationId.of(frame.get("id")),
                            decodeLines(frame.get("lines")),
                            Instant.parse(frame.get("at")));
            case "released" ->
                    new Event.StockReleased(
                            ReservationId.of(frame.get("id")),
                            decodeLines(frame.get("lines")),
                            Instant.parse(frame.get("at")));
            case "expired" ->
                    new Event.StockExpired(
                            ReservationId.of(frame.get("id")),
                            decodeLines(frame.get("lines")),
                            Instant.parse(frame.get("at")));
            case "adjusted" ->
                    new Event.StockAdjusted(
                            IdempotencyKey.of(frame.get("key")),
                            Sku.of(frame.get("sku")),
                            frame.getLong("delta"),
                            frame.getLong("onhand"),
                            frame.getLong("reserved"),
                            Instant.parse(frame.get("at")));
            default -> throw new IllegalArgumentException("unknown event kind '" + frame.kind() + "' in: " + text);
        };
    }

    private static String movement(String kind, ReservationId id, List<Line> lines, Instant at) {
        return head(kind)
                .put("id", id.value())
                .put("at", at.toString())
                .put("lines", encodeLines(lines))
                .done();
    }

    /**
     * Writes lines as {@code sku:qty,sku:qty}.
     *
     * <p>Public because an adapter storing a reservation needs the same form the outcome uses, and
     * two implementations of one format is one implementation too many.
     *
     * @param lines the lines, in the order they should be written
     * @return the encoded form, never empty
     */
    public static String encodeLines(List<Line> lines) {
        StringBuilder sb = new StringBuilder();
        for (Line line : lines) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(line.sku()).append(':').append(line.quantity());
        }
        return sb.toString();
    }

    /**
     * Reverses {@link #encodeLines}.
     *
     * @param text the encoded form
     * @return the lines
     * @throws IllegalArgumentException if the text is malformed
     */
    public static List<Line> decodeLines(String text) {
        List<Line> lines = new ArrayList<>();
        for (String part : text.split(",", -1)) {
            int colon = part.lastIndexOf(':');
            if (colon < 0) {
                throw new IllegalArgumentException("malformed line '" + part + "'");
            }
            lines.add(new Line(Sku.of(part.substring(0, colon)), Long.parseLong(part.substring(colon + 1))));
        }
        return lines;
    }

    private static String encodeShortfalls(List<Outcome.Shortfall> shortfalls) {
        StringBuilder sb = new StringBuilder();
        for (Outcome.Shortfall s : shortfalls) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(s.sku()).append(':').append(s.requested()).append(':').append(s.available());
        }
        return sb.toString();
    }

    private static List<Outcome.Shortfall> decodeShortfalls(String text) {
        if (text.isEmpty()) {
            return List.of();
        }
        List<Outcome.Shortfall> shortfalls = new ArrayList<>();
        for (String part : text.split(",", -1)) {
            String[] bits = part.split(":", -1);
            if (bits.length != 3) {
                throw new IllegalArgumentException("malformed shortfall '" + part + "'");
            }
            shortfalls.add(
                    new Outcome.Shortfall(Sku.of(bits[0]), Long.parseLong(bits[1]), Long.parseLong(bits[2])));
        }
        return shortfalls;
    }

    /**
     * Makes free text safe to put in a space-separated token.
     *
     * @param raw the text
     * @return the text with backslashes and whitespace escaped
     */
    static String escape(String raw) {
        StringBuilder sb = new StringBuilder(raw.length() + 8);
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case ' ' -> sb.append("\\s");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * Reverses {@link #escape}.
     *
     * @param escaped the escaped text
     * @return the original text
     * @throws IllegalArgumentException on an escape this build does not write
     */
    static String unescape(String escaped) {
        StringBuilder sb = new StringBuilder(escaped.length());
        for (int i = 0; i < escaped.length(); i++) {
            char c = escaped.charAt(i);
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            if (++i == escaped.length()) {
                throw new IllegalArgumentException("text ends in a dangling escape: " + escaped);
            }
            char next = escaped.charAt(i);
            switch (next) {
                case '\\' -> sb.append('\\');
                case 's' -> sb.append(' ');
                case 'n' -> sb.append('\n');
                case 'r' -> sb.append('\r');
                case 't' -> sb.append('\t');
                default -> throw new IllegalArgumentException("unknown escape '\\" + next + "' in: " + escaped);
            }
        }
        return sb.toString();
    }

    private static Builder head(String kind) {
        return new Builder(kind);
    }

    /** Accumulates {@code name=value} tokens after the version tag and the kind. */
    private static final class Builder {
        private final StringBuilder sb = new StringBuilder(96);

        private Builder(String kind) {
            sb.append(VERSION).append(' ').append(kind);
        }

        private Builder put(String name, String value) {
            sb.append(' ').append(name).append('=').append(value);
            return this;
        }

        private String done() {
            return sb.toString();
        }
    }

    /** One parsed line: its kind and its tokens. */
    private record Frame(String kind, Map<String, String> fields) {

        private static Frame parse(String text) {
            if (text == null || text.isBlank()) {
                throw new IllegalArgumentException("nothing to decode");
            }
            String[] tokens = text.split(" ");
            if (tokens.length < 2) {
                throw new IllegalArgumentException("truncated record: " + text);
            }
            if (!VERSION.equals(tokens[0])) {
                // A reader that guessed here would misread a future format as this one and hand a
                // caller values that are wrong rather than absent.
                throw new IllegalArgumentException(
                        "unsupported format '" + tokens[0] + "', this build writes " + VERSION);
            }
            Map<String, String> fields = new LinkedHashMap<>();
            for (int i = 2; i < tokens.length; i++) {
                int eq = tokens[i].indexOf('=');
                if (eq < 0) {
                    throw new IllegalArgumentException("malformed token '" + tokens[i] + "' in: " + text);
                }
                fields.put(tokens[i].substring(0, eq), tokens[i].substring(eq + 1));
            }
            return new Frame(tokens[1], fields);
        }

        private String get(String name) {
            String value = fields.get(name);
            if (value == null) {
                throw new IllegalArgumentException("missing field '" + name + "' in a " + kind + " record");
            }
            return value;
        }

        private long getLong(String name) {
            return Long.parseLong(get(name));
        }
    }
}
