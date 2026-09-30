package io.till.loadtest;

import java.util.Map;

/** Just enough JSON to write the provider's answers: objects of strings, numbers, booleans and string arrays. */
final class Json {

    private Json() {}

    static String object(Map<String, Object> members) {
        StringBuilder json = new StringBuilder("{");
        for (Map.Entry<String, Object> member : members.entrySet()) {
            if (json.length() > 1) {
                json.append(',');
            }
            string(json, member.getKey());
            json.append(':');
            value(json, member.getValue());
        }
        return json.append('}').toString();
    }

    private static void value(StringBuilder json, Object value) {
        switch (value) {
            case String text -> string(json, text);
            case Number number -> json.append(number);
            case Boolean bool -> json.append(bool);
            case String[] texts -> {
                json.append('[');
                for (int i = 0; i < texts.length; i++) {
                    if (i > 0) {
                        json.append(',');
                    }
                    string(json, texts[i]);
                }
                json.append(']');
            }
            default -> throw new IllegalArgumentException("not a JSON value here: " + value.getClass());
        }
    }

    private static void string(StringBuilder json, String text) {
        json.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> json.append("\\\"");
                case '\\' -> json.append("\\\\");
                case '\n' -> json.append("\\n");
                case '\r' -> json.append("\\r");
                case '\t' -> json.append("\\t");
                default -> {
                    if (c < 0x20) {
                        json.append(String.format("\\u%04x", (int) c));
                    } else {
                        json.append(c);
                    }
                }
            }
        }
        json.append('"');
    }
}
