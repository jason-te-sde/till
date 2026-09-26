package io.till.store;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.till.core.IdempotencyKey;
import io.till.core.Line;
import io.till.core.Outcome;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Browsing: the half of the store anybody can use without signing in.
 *
 * <p>Asserted against the catalogue the migrations actually seed — thirty-two games, eight genres of
 * four, six on sale — so a migration that drops a row or mistypes a genre is a failure here rather than
 * a gap on a page.
 */
class CatalogueApiTest extends StoreTest {

    @Nested
    @DisplayName("the home page")
    class HomePage {

        @Test
        @DisplayName("arrives in one request, with every row it shows")
        void oneRequest() throws Exception {
            mvc.perform(get("/api/home"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.featured[*].sku", contains("sunless-orbit", "rust-and-rain", "deep-field", "ninefold")))
                    .andExpect(jsonPath("$.bestSellers", hasSize(8)))
                    .andExpect(jsonPath("$.newReleases[0].sku").value("ninefold"))
                    .andExpect(jsonPath("$.onSale[0].sku").value("undertow"))
                    .andExpect(jsonPath("$.onSale[0].discountPercent").value(35))
                    .andExpect(jsonPath("$.genres", hasSize(8)))
                    .andExpect(jsonPath("$.genres[*].count", everyItem(is(4))));
        }

        @Test
        @DisplayName("before anything has sold, best sellers fall back to the curated order")
        void bestSellersWithNoSales() throws Exception {
            // Every game ties at zero sales, and the tie breaks by the featured rank — so a brand-new
            // store's home page opens on its featured games, not on whatever sorts first alphabetically.
            mvc.perform(get("/api/home"))
                    .andExpect(jsonPath("$.bestSellers[0].sku").value("sunless-orbit"))
                    .andExpect(jsonPath("$.bestSellers[3].sku").value("ninefold"));
        }

        @Test
        @DisplayName("once games sell, the best sellers are what sold")
        void bestSellersFollowSales() throws Exception {
            sell("canopy", 3);
            sell("hexfall", 1);
            ledger.deliver(projector);

            mvc.perform(get("/api/home"))
                    .andExpect(jsonPath("$.bestSellers[0].sku").value("canopy"))
                    .andExpect(jsonPath("$.bestSellers[1].sku").value("hexfall"));
        }

        @Test
        @DisplayName("a sale older than a week no longer counts")
        void theWindowSlides() throws Exception {
            sell("canopy", 5);
            ledger.deliver(projector);
            clock.advance(Duration.ofDays(8));
            sell("hexfall", 1);
            ledger.deliver(projector);

            mvc.perform(get("/api/home")).andExpect(jsonPath("$.bestSellers[0].sku").value("hexfall"));
        }
    }

    @Nested
    @DisplayName("browsing and search")
    class Browsing {

        @Test
        @DisplayName("lists the catalogue a page at a time, with the total")
        void pages() throws Exception {
            mvc.perform(get("/api/games").param("size", "10"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items", hasSize(10)))
                    .andExpect(jsonPath("$.total").value(32))
                    .andExpect(jsonPath("$.page").value(0));
            mvc.perform(get("/api/games").param("size", "10").param("page", "3"))
                    .andExpect(jsonPath("$.items", hasSize(2)));
        }

        @Test
        @DisplayName("a page past the end is empty but still knows the total")
        void pastTheEnd() throws Exception {
            // No rows means no window-function total to read, so this is the path that falls back to a
            // count — and the one that would quietly report zero if it did not.
            mvc.perform(get("/api/games").param("size", "10").param("page", "4"))
                    .andExpect(jsonPath("$.items", hasSize(0)))
                    .andExpect(jsonPath("$.total").value(32));
        }

        @Test
        @DisplayName("finds games by the words in them, best match first")
        void textSearch() throws Exception {
            // "harbour" is in Ledger of Tides' blurb and description and in its studio's name; Rust and
            // Rain only shares the studio. The weighting is what puts the right one first.
            mvc.perform(get("/api/games").param("q", "harbour"))
                    .andExpect(jsonPath("$.items[0].sku").value("ledger-of-tides"))
                    .andExpect(jsonPath("$.items[*].sku", hasItem("rust-and-rain")));
        }

        @Test
        @DisplayName("finds a title from the half-word a search box is always looking at")
        void partialTitle() throws Exception {
            mvc.perform(get("/api/games").param("q", "sunl"))
                    .andExpect(jsonPath("$.items[*].sku", contains("sunless-orbit")));
        }

        @Test
        @DisplayName("treats a wildcard character in the search box as text, not as a wildcard")
        void likeIsEscaped() throws Exception {
            mvc.perform(get("/api/games").param("q", "%")).andExpect(jsonPath("$.total").value(0));
            mvc.perform(get("/api/games").param("q", "_")).andExpect(jsonPath("$.total").value(0));
        }

        @Test
        @DisplayName("filters by genre, tag, price and discount, together")
        void filters() throws Exception {
            mvc.perform(get("/api/games").param("genre", "Puzzle"))
                    .andExpect(jsonPath("$.total").value(4))
                    .andExpect(jsonPath("$.items[*].genre", everyItem(is("Puzzle"))));
            mvc.perform(get("/api/games").param("tag", "co-op"))
                    .andExpect(jsonPath("$.total").value(5))
                    .andExpect(jsonPath("$.items[*].tags", everyItem(hasItem("co-op"))));
            mvc.perform(get("/api/games").param("maxPriceCents", "1500"))
                    .andExpect(jsonPath("$.total").value(5))
                    .andExpect(jsonPath("$.items[*].priceCents", everyItem(lessThanOrEqualTo(1500))));
            mvc.perform(get("/api/games").param("onSale", "true"))
                    .andExpect(jsonPath("$.total").value(6));
            mvc.perform(get("/api/games").param("onSale", "true").param("genre", "Strategy"))
                    .andExpect(jsonPath("$.items[*].sku", contains("quiet-frontier")));
        }

        @Test
        @DisplayName("counts each genre under the other filters, so choosing one does not zero the rest")
        void facets() throws Exception {
            mvc.perform(get("/api/games").param("genre", "Puzzle"))
                    // Still all eight genres, four each — the genre filter does not narrow its own facet.
                    .andExpect(jsonPath("$.facets.genres", hasSize(8)))
                    .andExpect(jsonPath("$.facets.genres[*].count", everyItem(is(4))))
                    // But the tags are the puzzle games' tags only.
                    .andExpect(jsonPath("$.facets.tags[*].value", not(hasItem("co-op"))));
        }

        @Test
        @DisplayName("sorts by price, by newest and by name")
        void sorts() throws Exception {
            mvc.perform(get("/api/games").param("sort", "price-asc").param("size", "1"))
                    .andExpect(jsonPath("$.items[0].sku").value("tin-soldier-hop"));
            mvc.perform(get("/api/games").param("sort", "price-desc").param("size", "1"))
                    .andExpect(jsonPath("$.items[0].sku").value("ashen-crown"));
            mvc.perform(get("/api/games").param("sort", "newest").param("size", "1"))
                    .andExpect(jsonPath("$.items[0].sku").value("ninefold"));
            mvc.perform(get("/api/games").param("sort", "title").param("size", "1"))
                    .andExpect(jsonPath("$.items[0].title").value("Ashen Crown"));
        }

        @Test
        @DisplayName("refuses a sort, a page size or a depth it does not serve")
        void refusesNonsense() throws Exception {
            mvc.perform(get("/api/games").param("sort", "cheapest-first"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
            mvc.perform(get("/api/games").param("size", "500")).andExpect(status().isBadRequest());
            mvc.perform(get("/api/games").param("page", "900").param("size", "48")).andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("may be cached publicly for a few seconds")
        void cacheable() throws Exception {
            mvc.perform(get("/api/games"))
                    .andExpect(header().string("Cache-Control", containsString("max-age=5")))
                    .andExpect(header().string("Cache-Control", containsString("public")));
        }
    }

    @Nested
    @DisplayName("a game's page")
    class GamePage {

        @Test
        @DisplayName("has the description, the selling points and some related games")
        void detail() throws Exception {
            mvc.perform(get("/api/games/sunless-orbit"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.game.title").value("Sunless Orbit"))
                    .andExpect(jsonPath("$.game.priceCents").value(4499))
                    .andExpect(jsonPath("$.game.listPriceCents").value(5999))
                    .andExpect(jsonPath("$.game.discountPercent").value(25))
                    .andExpect(jsonPath("$.game.currency").value("USD"))
                    .andExpect(jsonPath("$.features", hasSize(3)))
                    .andExpect(jsonPath("$.related", hasSize(4)))
                    .andExpect(jsonPath("$.related[*].sku", not(hasItem("sunless-orbit"))))
                    .andExpect(jsonPath("$.related[0].genre").value("Survival"));
        }

        @Test
        @DisplayName("says a game nobody has heard about is sold out, and that it does not know")
        void unknownAvailability() throws Exception {
            mvc.perform(get("/api/games/tessera"))
                    .andExpect(jsonPath("$.game.available").value(0))
                    .andExpect(jsonPath("$.game.availabilityAsOf", nullValue()));
        }

        @Test
        @DisplayName("shows what the ledger's events say is left, stamped with the ledger's clock")
        void availabilityFromEvents() throws Exception {
            ledger.stock("tessera", 10);
            ledger.deliver(projector);

            mvc.perform(get("/api/games/tessera"))
                    .andExpect(jsonPath("$.game.available").value(10))
                    .andExpect(jsonPath("$.game.availabilityAsOf").value("2026-09-18T12:00:00Z"));
        }

        @Test
        @DisplayName("is a 404 problem for a game the store does not sell")
        void notFound() throws Exception {
            mvc.perform(get("/api/games/no-such-game"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                    .andExpect(jsonPath("$.detail", containsString("no-such-game")));
        }
    }

    /** A sale made straight against the ledger, as any other channel it serves could make one. */
    private void sell(String sku, long units) {
        ledger.stock(sku, units);
        Outcome held = ledger.reserve(IdempotencyKey.of("sale-" + sku + "-" + clock.instant().toEpochMilli()),
                List.of(Line.of(sku, units)), Duration.ofMinutes(15));
        ledger.commit(IdempotencyKey.of("pay-" + sku + "-" + clock.instant().toEpochMilli()), ((Outcome.Reserved) held).id());
    }
}
