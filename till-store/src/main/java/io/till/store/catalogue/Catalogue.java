package io.till.store.catalogue;

import static io.till.store.catalogue.CatalogueCache.Region.SALES;
import static io.till.store.catalogue.CatalogueCache.Region.SEARCHES;
import static io.till.store.catalogue.CatalogueCache.Region.STABLE;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;

/**
 * The catalogue as the storefront reads it: {@link Games}, through {@link CatalogueCache}. Each read
 * says how long its answer can be trusted, and the cache does the rest.
 *
 * <p>Only the storefront's reads come through here. Checkout prices an order from {@link Games}
 * directly, so a price is always the database's.
 */
@Component
class Catalogue {

    private static final TypeReference<Games.SearchResult> SEARCH_RESULT = new TypeReference<>() {};
    private static final TypeReference<Game> GAME = new TypeReference<>() {};
    private static final TypeReference<List<Game>> GAMES = new TypeReference<>() {};
    private static final TypeReference<List<Games.Facet>> FACETS = new TypeReference<>() {};

    private final Games games;
    private final CatalogueCache cache;

    Catalogue(Games games, CatalogueCache cache) {
        this.games = games;
        this.cache = cache;
    }

    Games.SearchResult search(GameQuery query) {
        String key = "search:" + parts(
                query.text(), query.genre(), query.tag(), query.maxPriceCents(), query.onSale(), query.sort(),
                query.page(), query.size());
        return cache.get(SEARCHES, key, SEARCH_RESULT, () -> games.search(query));
    }

    /**
     * Only a game that exists is kept. A key for every made-up SKU would let anybody fill the cache
     * with nothing.
     */
    Optional<Game> find(String sku) {
        return Optional.ofNullable(cache.get(STABLE, "game:" + parts(sku), GAME, () -> games.find(sku).orElse(null)));
    }

    List<Game> related(Game game, int limit) {
        return cache.get(SALES, "related:" + parts(game.sku(), limit), GAMES, () -> games.related(game, limit));
    }

    List<Game> featured() {
        return cache.get(STABLE, "featured", GAMES, games::featured);
    }

    List<Game> bestSellers(int limit) {
        return cache.get(SALES, "best-sellers:" + limit, GAMES, () -> games.bestSellers(limit));
    }

    List<Game> newReleases(int limit) {
        return cache.get(STABLE, "new-releases:" + limit, GAMES, () -> games.newReleases(limit));
    }

    List<Game> onSale(int limit) {
        return cache.get(STABLE, "on-sale:" + limit, GAMES, () -> games.onSale(limit));
    }

    List<Games.Facet> genres() {
        return cache.get(STABLE, "genres", FACETS, games::genres);
    }

    /** A key from parts: each one encoded, so no part can contain the separator. */
    private static String parts(Object... parts) {
        return Stream.of(parts)
                .map(part -> part == null ? "~" : URLEncoder.encode(part.toString(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("|"));
    }
}
