package io.till.store.catalogue;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.till.store.StoreProperties;
import io.till.store.catalogue.CatalogueViews.Facets;
import io.till.store.catalogue.CatalogueViews.GameCard;
import io.till.store.catalogue.CatalogueViews.GameDetail;
import io.till.store.catalogue.CatalogueViews.GamePage;
import io.till.store.catalogue.CatalogueViews.Home;
import io.till.store.web.NotFoundException;
import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The catalogue, for anybody — no sign-in needed to browse.
 *
 * <p>Every response is marked cacheable for a few seconds, publicly. A listing is the same for every
 * visitor, so the proxy in front can answer a burst of identical requests from one fetch; and the one
 * part that goes stale quickly, availability, is already allowed to lag — the ledger decides at
 * checkout. Five seconds keeps a store page honest to within one refresh.
 *
 * <p>How stale a cache may serve is bounded here, by the origin, rather than left to the cache: while
 * it refetches, up to thirty seconds; while this service is down, up to five minutes. A cache told
 * only "serve stale while updating" has no upper bound at all, and one refetch it cannot store is
 * enough to freeze an entry for as long as the traffic keeps it warm.
 */
@RestController
@RequestMapping("/api")
@Tag(name = "catalogue", description = "Browsing and search. Public.")
class CatalogueController {

    private static final CacheControl BRIEFLY_PUBLIC = CacheControl.maxAge(Duration.ofSeconds(5))
            .cachePublic()
            .staleWhileRevalidate(Duration.ofSeconds(30))
            .staleIfError(Duration.ofMinutes(5));
    private static final int ROW = 8;

    private final Games games;
    private final Availability availability;
    private final String currency;

    CatalogueController(Games games, Availability availability, StoreProperties properties) {
        this.games = games;
        this.availability = availability;
        this.currency = properties.checkout().currency();
    }

    @Operation(operationId = "searchGames", summary = "Browse and search the catalogue")
    @GetMapping("/games")
    ResponseEntity<GamePage> search(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String genre,
            @RequestParam(required = false) String tag,
            @RequestParam(required = false) Long maxPriceCents,
            @RequestParam(defaultValue = "false") boolean onSale,
            @RequestParam(required = false) String sort,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "24") int size) {
        GameQuery query = new GameQuery(q, genre, tag, maxPriceCents, onSale, GameQuery.Sort.parse(sort), page, size);
        Games.SearchResult result = games.search(query);
        GamePage body = new GamePage(
                cards(result.games()), query.page(), query.size(), result.total(), new Facets(result.genres(), result.tags()));
        return ResponseEntity.ok().cacheControl(BRIEFLY_PUBLIC).body(body);
    }

    @Operation(operationId = "getGame", summary = "One game's page")
    @GetMapping("/games/{sku}")
    ResponseEntity<GameDetail> game(@PathVariable String sku) {
        Game game = games.find(sku).orElseThrow(() -> new NotFoundException("No such game", "the store does not sell '" + sku + "'"));
        List<Game> related = games.related(game, 4);
        Map<String, Availability.Level> levels =
                availability.of(Stream.concat(Stream.of(game), related.stream()).map(Game::sku).toList());
        GameDetail body = new GameDetail(
                GameCard.of(game, levels.get(game.sku()), currency),
                game.description(),
                game.features(),
                related.stream().map(r -> GameCard.of(r, levels.get(r.sku()), currency)).toList());
        return ResponseEntity.ok().cacheControl(BRIEFLY_PUBLIC).body(body);
    }

    @Operation(operationId = "getHome", summary = "Everything the home page shows, in one request")
    @GetMapping("/home")
    ResponseEntity<Home> home() {
        List<Game> featured = games.featured();
        List<Game> bestSellers = games.bestSellers(ROW);
        List<Game> newReleases = games.newReleases(ROW);
        List<Game> onSale = games.onSale(ROW);

        // One availability query across all four rows, not four — a game can appear in several.
        Set<String> skus = new LinkedHashSet<>();
        Stream.of(featured, bestSellers, newReleases, onSale).flatMap(List::stream).map(Game::sku).forEach(skus::add);
        Map<String, Availability.Level> levels = availability.of(skus);

        Home body = new Home(
                cards(featured, levels),
                cards(bestSellers, levels),
                cards(newReleases, levels),
                cards(onSale, levels),
                games.genres());
        return ResponseEntity.ok().cacheControl(BRIEFLY_PUBLIC).body(body);
    }

    @Operation(operationId = "listGenres", summary = "Every genre, with how many games it has")
    @GetMapping("/genres")
    ResponseEntity<List<Games.Facet>> genres() {
        return ResponseEntity.ok().cacheControl(BRIEFLY_PUBLIC).body(games.genres());
    }

    private List<GameCard> cards(Collection<Game> page) {
        return cards(page, availability.of(page.stream().map(Game::sku).toList()));
    }

    private List<GameCard> cards(Collection<Game> page, Map<String, Availability.Level> levels) {
        return page.stream().map(game -> GameCard.of(game, levels.get(game.sku()), currency)).toList();
    }
}
