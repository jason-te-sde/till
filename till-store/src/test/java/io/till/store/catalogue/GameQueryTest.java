package io.till.store.catalogue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class GameQueryTest {

    @Test
    @DisplayName("blank filters are no filter at all, and text is trimmed")
    void blanks() {
        GameQuery query = new GameQuery("  rust  ", " ", "", null, false, null, 0, 24);
        assertEquals("rust", query.text());
        assertNull(query.genre());
        assertNull(query.tag());
    }

    @Test
    @DisplayName("sorts by relevance when there is text to be relevant to, and by the curated order when not")
    void defaultSort() {
        assertEquals(GameQuery.Sort.RELEVANCE, new GameQuery("rust", null, null, null, false, null, 0, 24).sort());
        assertEquals(GameQuery.Sort.FEATURED, new GameQuery(null, null, null, null, false, null, 0, 24).sort());
        assertEquals(GameQuery.Sort.NEWEST, new GameQuery("rust", null, null, null, false, GameQuery.Sort.NEWEST, 0, 24).sort());
    }

    @Test
    @DisplayName("refuses page sizes, depths, prices and text it will not serve")
    void refuses() {
        assertThrows(IllegalArgumentException.class, () -> new GameQuery(null, null, null, null, false, null, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new GameQuery(null, null, null, null, false, null, 0, GameQuery.MAX_SIZE + 1));
        assertThrows(IllegalArgumentException.class, () -> new GameQuery(null, null, null, null, false, null, -1, 24));
        assertThrows(IllegalArgumentException.class, () -> new GameQuery(null, null, null, -1L, false, null, 0, 24));
        assertThrows(IllegalArgumentException.class,
                () -> new GameQuery("x".repeat(GameQuery.MAX_TEXT + 1), null, null, null, false, null, 0, 24));
    }

    @Test
    @DisplayName("serves down to the deepest offset, and not one page past it")
    void depth() {
        int lastPage = GameQuery.MAX_OFFSET / 40;
        new GameQuery(null, null, null, null, false, null, lastPage, 40);
        assertThrows(IllegalArgumentException.class, () -> new GameQuery(null, null, null, null, false, null, lastPage + 1, 40));
    }

    @Test
    @DisplayName("reads sorts the way a URL spells them")
    void parse() {
        assertEquals(GameQuery.Sort.PRICE_ASC, GameQuery.Sort.parse("price-asc"));
        assertEquals(GameQuery.Sort.BESTSELLING, GameQuery.Sort.parse(" Bestselling "));
        assertNull(GameQuery.Sort.parse(null));
        assertNull(GameQuery.Sort.parse(""));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> GameQuery.Sort.parse("cheapest-first"));
        assertEquals("unknown sort 'cheapest-first'", e.getMessage());
    }
}
