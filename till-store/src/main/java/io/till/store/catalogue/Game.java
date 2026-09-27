package io.till.store.catalogue;

import java.time.LocalDate;
import java.util.List;

/**
 * One product in the catalogue.
 *
 * <p>Everything here changes when somebody edits a store page, and nothing here changes when a copy
 * is sold. Stock lives in the ledger and availability in its own table, for that reason: a price
 * change and a sale should never contend for the same row.
 *
 * @param sku the SKU, which is also what the ledger knows the game by
 * @param title its name
 * @param studio who made it
 * @param genre one genre per game
 * @param priceCents what it costs now, in minor units — never a floating point type, which is a
 *     rounding error waiting for a report somebody reconciles
 * @param listPriceCents what it costs when not on sale; equal to {@code priceCents} otherwise
 * @param releasedOn release date
 * @param cover the artwork motif; the browser draws the cover from it
 * @param blurb one or two sentences, for a card
 * @param description a paragraph, for the game's page
 * @param features three selling points
 * @param tags what a customer filters by
 * @param featuredRank position in the home page's featured row, or null
 */
public record Game(
        String sku,
        String title,
        String studio,
        String genre,
        long priceCents,
        long listPriceCents,
        LocalDate releasedOn,
        String cover,
        String blurb,
        String description,
        List<String> features,
        List<String> tags,
        Integer featuredRank) {

    public Game {
        features = List.copyOf(features);
        tags = List.copyOf(tags);
    }

    /**
     * @return whether it is currently discounted
     */
    public boolean onSale() {
        return priceCents < listPriceCents;
    }

    /**
     * The discount, rounded to a whole percent.
     *
     * <p>Computed rather than stored, so it can never disagree with the two prices it comes from.
     *
     * @return 0 when not on sale
     */
    public int discountPercent() {
        if (!onSale() || listPriceCents == 0) {
            return 0;
        }
        return (int) Math.round(100.0 * (listPriceCents - priceCents) / listPriceCents);
    }
}
