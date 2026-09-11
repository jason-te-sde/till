package io.till.core;

/**
 * Shared validation for the three identifier types.
 *
 * <p>The character set is deliberately narrow. These strings end up in log lines, in metric labels,
 * in outbox dedupe keys, and in URLs, and every one of those places has a character that changes its
 * meaning. Rejecting them at the boundary is cheaper than escaping them at four call sites and
 * remembering to do it at the fifth.
 */
final class Ids {

    private Ids() {}

    /**
     * Checks an identifier and returns it unchanged.
     *
     * @param kind what to call the value in the failure message
     * @param value the candidate
     * @param maxLength longest accepted, in characters
     * @return {@code value}
     * @throws IllegalArgumentException if the value is null, empty, too long, does not start with a
     *     letter or digit, or contains a character outside {@code A-Za-z0-9._:@=+/-}
     */
    static String check(String kind, String value, int maxLength) {
        if (value == null) {
            throw new IllegalArgumentException(kind + " must not be null");
        }
        if (value.isEmpty()) {
            throw new IllegalArgumentException(kind + " must not be empty");
        }
        if (value.length() > maxLength) {
            throw new IllegalArgumentException(
                    kind + " must be at most " + maxLength + " characters, got " + value.length());
        }
        if (!isLeading(value.charAt(0))) {
            throw new IllegalArgumentException(
                    kind + " must start with a letter or digit: '" + value + "'");
        }
        for (int i = 1; i < value.length(); i++) {
            if (!isTrailing(value.charAt(i))) {
                throw new IllegalArgumentException(
                        kind
                                + " contains an unsupported character at index "
                                + i
                                + " (allowed: A-Za-z0-9._:@=+/-): '"
                                + value
                                + "'");
            }
        }
        return value;
    }

    private static boolean isLeading(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
    }

    private static boolean isTrailing(char c) {
        return isLeading(c)
                || c == '.'
                || c == '_'
                || c == ':'
                || c == '@'
                || c == '='
                || c == '+'
                || c == '/'
                || c == '-';
    }
}
