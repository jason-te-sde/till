package io.till.store.catalogue;

import java.util.Locale;

/**
 * What a customer is browsing for.
 *
 * <p>Validated on construction, so the repository can build SQL from it without re-checking: every
 * value that reaches a query is either bound as a parameter or chosen from {@link Sort}'s fixed set of
 * {@code order by} clauses. Nothing a customer types is ever concatenated into SQL.
 *
 * @param text free text, or null
 * @param genre exactly one genre, or null
 * @param tag exactly one tag, or null
 * @param maxPriceCents an upper bound on the current price, or null
 * @param onSale only discounted games
 * @param sort the order
 * @param page zero-based
 * @param size games per page
 */
public record GameQuery(
        String text, String genre, String tag, Long maxPriceCents, boolean onSale, Sort sort, int page, int size) {

    /** Largest page a client may ask for. */
    public static final int MAX_SIZE = 48;

    /**
     * Deepest offset served. Past this a client is walking the whole catalogue page by page, which an
     * offset makes quadratic; a search box is the better tool, and nobody reads page 400.
     */
    public static final int MAX_OFFSET = 10_000;

    /** Longest search text accepted. */
    public static final int MAX_TEXT = 100;

    public GameQuery {
        text = blankToNull(text);
        genre = blankToNull(genre);
        tag = blankToNull(tag);
        if (text != null && text.length() > MAX_TEXT) {
            throw new IllegalArgumentException("search text must be at most " + MAX_TEXT + " characters");
        }
        if (maxPriceCents != null && maxPriceCents < 0) {
            throw new IllegalArgumentException("maxPriceCents must not be negative");
        }
        if (size < 1 || size > MAX_SIZE) {
            throw new IllegalArgumentException("size must be between 1 and " + MAX_SIZE + ", got " + size);
        }
        if (page < 0) {
            throw new IllegalArgumentException("page must not be negative, got " + page);
        }
        if ((long) page * size > MAX_OFFSET) {
            throw new IllegalArgumentException("that page is past the end of what is browsable; search instead");
        }
        if (sort == null) {
            sort = text == null ? Sort.FEATURED : Sort.RELEVANCE;
        }
    }

    /**
     * @return whether there is free text to match
     */
    public boolean hasText() {
        return text != null;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    /** The orders a customer can ask for. The {@code order by} for each is fixed in the repository. */
    public enum Sort {
        /** Best text match first; the default whenever there is text. */
        RELEVANCE,
        /** The curated row first, then what is selling; the default otherwise. */
        FEATURED,
        /** Units sold in the last seven days. */
        BESTSELLING,
        /** Most recently released. */
        NEWEST,
        /** Cheapest first. */
        PRICE_ASC,
        /** Dearest first. */
        PRICE_DESC,
        /** Alphabetical. */
        TITLE;

        /**
         * Reads the form a URL uses: {@code price-asc}, {@code bestselling}.
         *
         * @param value the parameter, or null
         * @return the sort, or null if none was given
         * @throws IllegalArgumentException for a value that is not a sort
         */
        public static Sort parse(String value) {
            if (value == null || value.isBlank()) {
                return null;
            }
            try {
                return Sort.valueOf(value.strip().toUpperCase(Locale.ROOT).replace('-', '_'));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("unknown sort '" + value + "'", e);
            }
        }
    }
}
