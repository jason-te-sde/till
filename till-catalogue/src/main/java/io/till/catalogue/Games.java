package io.till.catalogue;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.stereotype.Component;

/**
 * The catalogue itself: what a game is called, what it costs, who made it.
 *
 * <p>Deliberately boring, and deliberately separate from availability. This half changes when
 * somebody in marketing edits a blurb; the other half changes thousands of times an hour. Joining
 * them in one table would put a price change and a stock movement in contention for the same row.
 */
@Component
class Games {

    private static final String COLUMNS =
            "sku, title, studio, genre, price_cents, released_on, cover, blurb";

    private static final String LIST =
            "select " + COLUMNS + " from catalogue_game order by title limit ?";

    private static final String BY_SKU = "select " + COLUMNS + " from catalogue_game where sku = ?";

    private final DataSource dataSource;

    Games(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * @param limit at most this many, in title order
     * @return the catalogue
     */
    List<Game> list(int limit) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(LIST)) {
            statement.setInt(1, limit);
            try (ResultSet rows = statement.executeQuery()) {
                List<Game> games = new ArrayList<>();
                while (rows.next()) {
                    games.add(read(rows));
                }
                return games;
            }
        } catch (SQLException e) {
            throw new CatalogueException("listing the catalogue", e);
        }
    }

    /**
     * @param sku the SKU
     * @return the game, or empty if the catalogue has no such thing
     */
    Optional<Game> find(String sku) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(BY_SKU)) {
            statement.setString(1, sku);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(read(rows)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new CatalogueException("reading " + sku, e);
        }
    }

    private static Game read(ResultSet rows) throws SQLException {
        return new Game(
                rows.getString("sku"),
                rows.getString("title"),
                rows.getString("studio"),
                rows.getString("genre"),
                rows.getLong("price_cents"),
                rows.getObject("released_on", LocalDate.class),
                rows.getString("cover"),
                rows.getString("blurb"));
    }

    /**
     * One product.
     *
     * @param sku the SKU, which is also what till knows it by
     * @param title its name
     * @param studio who made it
     * @param genre one word, for filtering
     * @param priceCents the price in minor units, because a price in a floating point type is a
     *     rounding error waiting for a quarterly report
     * @param releasedOn release date
     * @param cover a key the console maps to artwork, rather than a URL this service would then own
     * @param blurb one or two sentences
     */
    record Game(
            String sku,
            String title,
            String studio,
            String genre,
            long priceCents,
            LocalDate releasedOn,
            String cover,
            String blurb) {}
}
