package io.till.store.catalogue;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The catalogue: what games exist, what they cost, and how to find them.
 *
 * <p>Everything a customer can type or pick reaches SQL as a bound parameter. The only thing ever
 * concatenated is an {@code order by} clause, and those come from a fixed table keyed by
 * {@link GameQuery.Sort}, never from the request.
 */
@Repository
public class Games {

    /**
     * Seven days, today included, is what "best sellers this week" means here.
     *
     * <p>Public so that {@code StoreProperties.Sales} can refuse a {@code store.sales.retention}
     * shorter than it: a retention window that drops a partition this query still reads from would
     * be a misconfiguration, not a valid small number.
     */
    public static final int SALES_WINDOW_DAYS = 7;

    private static final String COLUMNS =
            "g.sku, g.title, g.studio, g.genre, g.price_cents, g.list_price_cents, g.released_on, g.cover, "
                    + "g.blurb, g.description, g.features, g.tags, g.featured_rank";

    /** Units sold per game in the window, joined wherever popularity decides the order. */
    private static final String SALES_JOIN =
            " left join (select sku, sum(units) as units from store_sales_daily where day >= ? group by sku) s"
                    + " on s.sku = g.sku";

    private final JdbcClient jdbc;
    private final Clock clock;

    Games(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /**
     * @param sku the SKU
     * @return the game, or empty if the catalogue has no such thing
     */
    public Optional<Game> find(String sku) {
        return jdbc.sql("select " + COLUMNS + " from store_game g where g.sku = ?")
                .param(sku)
                .query(Games::read)
                .optional();
    }

    /**
     * @param skus the SKUs
     * @return the games that exist, keyed by SKU; a SKU the catalogue does not have is simply absent
     */
    public Map<String, Game> findAll(Collection<String> skus) {
        if (skus.isEmpty()) {
            return Map.of();
        }
        Map<String, Game> found = new LinkedHashMap<>();
        jdbc.sql("select " + COLUMNS + " from store_game g where g.sku in (:skus)")
                .param("skus", skus)
                .query(Games::read)
                .list()
                .forEach(game -> found.put(game.sku(), game));
        return found;
    }

    /**
     * Browses the catalogue.
     *
     * <p>One round trip for the page and its total: {@code count(*) over ()} is evaluated before the
     * {@code limit}, so every row carries the number of matches. Only a page past the end — no rows,
     * so nothing to carry it — needs a second query.
     *
     * @param query what to look for
     * @return a page, its total, and the counts the filters would show
     */
    public SearchResult search(GameQuery query) {
        List<Object> params = new ArrayList<>();
        StringBuilder sql = new StringBuilder("select ").append(COLUMNS)
                .append(", count(*) over () as total");
        if (query.hasText()) {
            sql.append(", ts_rank_cd(g.search, websearch_to_tsquery('english', ?)) as text_rank");
            params.add(query.text());
        }
        sql.append(" from store_game g").append(SALES_JOIN);
        params.add(salesSince());

        Filter filter = Filter.of(query, true, true);
        sql.append(filter.where());
        params.addAll(filter.params());

        sql.append(" order by ").append(orderBy(query));
        sql.append(" limit ? offset ?");
        params.add(query.size());
        params.add(query.page() * query.size());

        long[] total = {0};
        List<Game> games = jdbc.sql(sql.toString())
                .params(params)
                .query((rs, row) -> {
                    total[0] = rs.getLong("total");
                    return read(rs, row);
                })
                .list();
        if (games.isEmpty() && query.page() > 0) {
            total[0] = count(filter);
        }
        return new SearchResult(games, total[0], genres(query), tags(query));
    }

    /**
     * @return every SKU in the catalogue, alphabetically
     */
    public List<String> skus() {
        return jdbc.sql("select sku from store_game order by sku").query(String.class).list();
    }

    /**
     * @return the curated featured row, in order
     */
    public List<Game> featured() {
        return jdbc.sql("select " + COLUMNS + " from store_game g where g.featured_rank is not null order by g.featured_rank")
                .query(Games::read)
                .list();
    }

    /**
     * What sold most in the last seven days.
     *
     * <p>Before anything has sold, every game ties at zero and the tie falls through to the curated
     * order — so a new store's home page shows its featured games rather than an alphabetical list.
     *
     * @param limit at most this many
     * @return best sellers, best first
     */
    public List<Game> bestSellers(int limit) {
        return jdbc.sql("select " + COLUMNS + " from store_game g" + SALES_JOIN
                        + " order by coalesce(s.units, 0) desc, g.featured_rank asc nulls last, g.released_on desc, g.sku"
                        + " limit ?")
                .params(salesSince(), limit)
                .query(Games::read)
                .list();
    }

    /**
     * @param limit at most this many
     * @return the most recently released
     */
    public List<Game> newReleases(int limit) {
        return jdbc.sql("select " + COLUMNS + " from store_game g order by g.released_on desc, g.sku limit ?")
                .param(limit)
                .query(Games::read)
                .list();
    }

    /**
     * @param limit at most this many
     * @return discounted games, deepest discount first
     */
    public List<Game> onSale(int limit) {
        return jdbc.sql("select " + COLUMNS + " from store_game g where g.price_cents < g.list_price_cents"
                        + " order by (g.list_price_cents - g.price_cents)::numeric / g.list_price_cents desc, g.sku limit ?")
                .param(limit)
                .query(Games::read)
                .list();
    }

    /**
     * Games to suggest beside this one: the same genre first, then the same studio.
     *
     * @param game the game being viewed
     * @param limit at most this many
     * @return related games, never including the game itself
     */
    public List<Game> related(Game game, int limit) {
        return jdbc.sql("select " + COLUMNS + " from store_game g" + SALES_JOIN
                        + " where g.sku <> ? and (g.genre = ? or g.studio = ?)"
                        + " order by (g.genre = ?) desc, coalesce(s.units, 0) desc, g.released_on desc, g.sku limit ?")
                .params(salesSince(), game.sku(), game.genre(), game.studio(), game.genre(), limit)
                .query(Games::read)
                .list();
    }

    /**
     * @return every genre with its number of games, alphabetically
     */
    public List<Facet> genres() {
        return jdbc.sql("select g.genre as value, count(*) as count from store_game g group by g.genre order by g.genre")
                .query((rs, row) -> new Facet(rs.getString("value"), rs.getLong("count")))
                .list();
    }

    // -------------------------------------------------------------------------------------------

    /**
     * Genre counts under every filter <i>except</i> the genre one.
     *
     * <p>That is what makes a facet useful: with "Puzzle" selected, the other genres still show how
     * many games they would offer, instead of all reading zero because the current filter excludes
     * them.
     */
    private List<Facet> genres(GameQuery query) {
        Filter filter = Filter.of(query, false, true);
        return jdbc.sql("select g.genre as value, count(*) as count from store_game g" + filter.where()
                        + " group by g.genre order by g.genre")
                .params(filter.params())
                .query((rs, row) -> new Facet(rs.getString("value"), rs.getLong("count")))
                .list();
    }

    /** Tag counts under every filter except the tag one, most common first. */
    private List<Facet> tags(GameQuery query) {
        Filter filter = Filter.of(query, true, false);
        return jdbc.sql("select t.tag as value, count(*) as count from store_game g cross join lateral unnest(g.tags) as t(tag)"
                        + filter.where() + " group by t.tag order by count(*) desc, t.tag")
                .params(filter.params())
                .query((rs, row) -> new Facet(rs.getString("value"), rs.getLong("count")))
                .list();
    }

    private long count(Filter filter) {
        return jdbc.sql("select count(*) from store_game g" + filter.where())
                .params(filter.params())
                .query(Long.class)
                .single();
    }

    private LocalDate salesSince() {
        return LocalDate.now(clock.withZone(ZoneOffset.UTC)).minusDays(SALES_WINDOW_DAYS - 1);
    }

    private static String orderBy(GameQuery query) {
        String featured = "g.featured_rank asc nulls last, coalesce(s.units, 0) desc, g.released_on desc, g.sku";
        return switch (query.sort()) {
            case RELEVANCE -> query.hasText() ? "text_rank desc, g.title, g.sku" : featured;
            case FEATURED -> featured;
            case BESTSELLING -> "coalesce(s.units, 0) desc, g.featured_rank asc nulls last, g.title, g.sku";
            case NEWEST -> "g.released_on desc, g.sku";
            case PRICE_ASC -> "g.price_cents asc, g.title, g.sku";
            case PRICE_DESC -> "g.price_cents desc, g.title, g.sku";
            case TITLE -> "g.title asc, g.sku";
        };
    }

    static Game read(ResultSet rs, int row) throws SQLException {
        return new Game(
                rs.getString("sku"),
                rs.getString("title"),
                rs.getString("studio"),
                rs.getString("genre"),
                rs.getLong("price_cents"),
                rs.getLong("list_price_cents"),
                rs.getObject("released_on", LocalDate.class),
                rs.getString("cover"),
                rs.getString("blurb"),
                rs.getString("description"),
                strings(rs.getArray("features")),
                strings(rs.getArray("tags")),
                (Integer) rs.getObject("featured_rank"));
    }

    private static List<String> strings(Array array) throws SQLException {
        if (array == null) {
            return List.of();
        }
        return List.of((String[]) array.getArray());
    }

    /**
     * The {@code where} clause for a query, built once and reused by the page, the count and both
     * facets so they cannot disagree about what "matching" means.
     */
    private record Filter(String where, List<Object> params) {

        static Filter of(GameQuery query, boolean withGenre, boolean withTag) {
            StringBuilder where = new StringBuilder(" where true");
            List<Object> params = new ArrayList<>();
            if (query.hasText()) {
                // Full-text for words, and a substring match on the title for the half-typed word a
                // search box is always looking at: "sunl" is not a lexeme, but it is the start of a
                // title the customer is plainly looking for.
                where.append(" and (g.search @@ websearch_to_tsquery('english', ?) or g.title ilike ? escape '\\')");
                params.add(query.text());
                params.add("%" + escapeLike(query.text()) + "%");
            }
            if (withGenre && query.genre() != null) {
                where.append(" and g.genre = ?");
                params.add(query.genre());
            }
            if (withTag && query.tag() != null) {
                where.append(" and g.tags @> array[?]::text[]");
                params.add(query.tag());
            }
            if (query.maxPriceCents() != null) {
                where.append(" and g.price_cents <= ?");
                params.add(query.maxPriceCents());
            }
            if (query.onSale()) {
                where.append(" and g.price_cents < g.list_price_cents");
            }
            return new Filter(where.toString(), List.copyOf(params));
        }

        /** So a customer searching for "100%" gets a match on the text rather than a wildcard. */
        private static String escapeLike(String text) {
            return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        }
    }

    /**
     * A page of results, with the facet counts that go beside it.
     *
     * @param games the page
     * @param total matches across every page
     * @param genres genre counts under the other filters
     * @param tags tag counts under the other filters
     */
    public record SearchResult(List<Game> games, long total, List<Facet> genres, List<Facet> tags) {}

    /**
     * One value a filter can take, and how many games it would show.
     *
     * @param value the genre or tag
     * @param count how many games have it
     */
    public record Facet(String value, long count) {}
}
