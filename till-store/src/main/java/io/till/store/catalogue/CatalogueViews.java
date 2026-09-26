package io.till.store.catalogue;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * What the catalogue endpoints return.
 *
 * <p>Separate from {@link Game} so the wire format is a contract rather than whatever the domain type
 * happens to look like this week. The browser's TypeScript is generated from these through the
 * OpenAPI document, so a field renamed here is a compile error in the frontend, not a blank on a page.
 */
public final class CatalogueViews {

    private CatalogueViews() {}

    /**
     * A game as a card on a listing.
     *
     * @param sku the SKU
     * @param title its name
     * @param studio who made it
     * @param genre its genre
     * @param priceCents the price now, in minor units
     * @param listPriceCents the price when not on sale
     * @param discountPercent whole-percent discount, 0 when not on sale
     * @param currency ISO 4217
     * @param releasedOn release date
     * @param cover artwork motif
     * @param blurb one or two sentences
     * @param tags filterable tags
     * @param available units the store believes can be bought, never negative
     * @param availabilityAsOf when that was last true, by the ledger's clock; null when nothing has
     *     been heard about this game at all, which reads as sold out rather than as unlimited
     */
    @Schema(name = "GameCard")
    public record GameCard(
            String sku,
            String title,
            String studio,
            String genre,
            long priceCents,
            long listPriceCents,
            int discountPercent,
            String currency,
            LocalDate releasedOn,
            String cover,
            String blurb,
            List<String> tags,
            long available,
            @Schema(nullable = true) Instant availabilityAsOf) {

        static GameCard of(Game game, Availability.Level level, String currency) {
            return new GameCard(
                    game.sku(),
                    game.title(),
                    game.studio(),
                    game.genre(),
                    game.priceCents(),
                    game.listPriceCents(),
                    game.discountPercent(),
                    currency,
                    game.releasedOn(),
                    game.cover(),
                    game.blurb(),
                    game.tags(),
                    level == null ? 0 : level.buyable(),
                    level == null ? null : level.updatedAt());
        }
    }

    /**
     * A game's own page.
     *
     * @param game the card's fields
     * @param description a paragraph
     * @param features three selling points
     * @param related a few games to suggest beside it
     */
    @Schema(name = "GameDetail")
    public record GameDetail(GameCard game, String description, List<String> features, List<GameCard> related) {}

    /**
     * One page of a listing.
     *
     * @param items the games
     * @param page zero-based
     * @param size requested page size
     * @param total matches across every page
     * @param facets what the filters would show
     */
    @Schema(name = "GamePage")
    public record GamePage(List<GameCard> items, int page, int size, long total, Facets facets) {}

    /**
     * Counts for the filter controls.
     *
     * @param genres per genre, under every other filter
     * @param tags per tag, under every other filter
     */
    @Schema(name = "Facets")
    public record Facets(List<Games.Facet> genres, List<Games.Facet> tags) {}

    /**
     * Everything the home page needs, in one request.
     *
     * <p>One call rather than five, because the page cannot render until all of them arrive, and five
     * round trips on a phone is the difference between a page and a spinner.
     *
     * @param featured the curated row
     * @param bestSellers what sold most this week
     * @param newReleases most recent first
     * @param onSale deepest discount first
     * @param genres every genre, for the navigation
     */
    @Schema(name = "Home")
    public record Home(
            List<GameCard> featured,
            List<GameCard> bestSellers,
            List<GameCard> newReleases,
            List<GameCard> onSale,
            List<Games.Facet> genres) {}
}
