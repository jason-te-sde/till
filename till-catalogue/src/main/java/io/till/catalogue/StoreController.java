package io.till.catalogue;

import io.till.catalogue.AvailabilityProjection.Availability;
import io.till.catalogue.Games.Game;
import io.till.client.TillApiException;
import io.till.client.TillClient;
import io.till.core.IdempotencyKey;
import io.till.core.Line;
import io.till.core.Outcome;
import io.till.core.ReservationId;
import io.till.core.Sku;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The shop front's API.
 *
 * <p>Two kinds of endpoint, and the difference between them is the point of this service existing.
 *
 * <p><b>Reads</b> come from this service's own tables: the catalogue, plus a projection of
 * availability that is as current as the last event it consumed. They are cheap, they do not touch
 * the ledger, and they scale by adding instances of this — which is what makes a storefront survive
 * being on a front page.
 *
 * <p><b>Writes</b> are forwarded to till over HTTP, with no shortcut. This service holds a client
 * token, not an admin one, and has no connection to the ledger's database. It therefore cannot
 * decide that a sale is allowed, and a bug here cannot oversell. The stale number in the read model
 * costs a customer one refused checkout; nothing else.
 */
@RestController
@RequestMapping("/v1")
class StoreController {

    private static final int MAX_PAGE = 100;

    private final Games games;
    private final AvailabilityProjection availability;
    private final TillClient till;

    StoreController(Games games, AvailabilityProjection availability, TillClient till) {
        this.games = games;
        this.availability = availability;
        this.till = till;
    }

    /**
     * @param limit at most this many games
     * @return the catalogue, each with what the storefront currently believes is buyable
     */
    @GetMapping("/games")
    GamePage list(@RequestParam(defaultValue = "50") int limit) {
        List<Game> catalogue = games.list(Math.clamp(limit, 1, MAX_PAGE));
        // One query for the page rather than one per game. The n+1 here would be invisible with
        // eight rows and ruinous with eight hundred.
        Map<String, Availability> levels =
                availability.of(catalogue.stream().map(Game::sku).toList()).stream()
                        .collect(Collectors.toMap(Availability::sku, Function.identity()));
        return new GamePage(catalogue.stream().map(game -> view(game, levels.get(game.sku()))).toList());
    }

    /**
     * @param sku the SKU
     * @return one game
     */
    @GetMapping("/games/{sku}")
    ResponseEntity<GameView> one(@PathVariable String sku) {
        return games.find(sku)
                .map(game -> ResponseEntity.ok(view(game, availability.of(sku).orElse(null))))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Takes a hold, by asking till.
     *
     * @param key the caller's idempotency key, passed straight through — the storefront does not
     *     mint one of its own, because a retry of this request must be a retry of the same
     *     reservation and only the caller knows which attempt it is on
     * @param request what to hold
     * @return the hold
     */
    @PostMapping("/checkout")
    ResponseEntity<Held> checkout(
            @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody CheckoutRequest request) {
        List<Line> lines = request.lines().stream()
                .map(line -> Line.of(line.sku(), line.quantity()))
                .toList();
        Outcome.Reserved held = till.reserve(IdempotencyKey.of(key), lines, null);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new Held(held.id().value(), held.expiresAt(), priceOf(lines)));
    }

    /**
     * @param key the caller's idempotency key
     * @param id the hold to turn into a sale
     * @return when it was sold
     */
    @PostMapping("/checkout/{id}/pay")
    Paid pay(@RequestHeader("Idempotency-Key") String key, @PathVariable String id) {
        Outcome.Committed committed = till.commit(IdempotencyKey.of(key), ReservationId.of(id));
        return new Paid(committed.id().value(), committed.at());
    }

    /**
     * @param key the caller's idempotency key
     * @param id the hold to give back
     * @return when it was given back
     */
    @PostMapping("/checkout/{id}/cancel")
    Paid cancel(@RequestHeader("Idempotency-Key") String key, @PathVariable String id) {
        Outcome.Released released = till.release(IdempotencyKey.of(key), ReservationId.of(id));
        return new Paid(released.id().value(), released.at());
    }

    /**
     * Passes till's refusal through unchanged.
     *
     * <p>Rewriting it would be the wrong instinct: the status, the {@code code} and the shortfalls
     * are the whole reason a browser can offer a smaller basket instead of a dead end, and a
     * storefront that flattened all of it to "sorry" would be throwing away the only information
     * the customer can act on.
     *
     * @param e what till said
     * @return the same answer, from here
     */
    @org.springframework.web.bind.annotation.ExceptionHandler(TillApiException.class)
    ResponseEntity<ProblemDetail> onTillRefusal(TillApiException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.valueOf(e.status()), e.getMessage());
        problem.setTitle("Checkout refused");
        e.rejection().ifPresent(code -> problem.setProperty("code", code.name()));
        if (!e.shortfalls().isEmpty()) {
            problem.setProperty("shortfalls", e.shortfalls());
        }
        return ResponseEntity.status(e.status()).body(problem);
    }

    private long priceOf(List<Line> lines) {
        return lines.stream()
                .mapToLong(line -> games.find(line.sku().value())
                        .map(game -> game.priceCents() * line.quantity())
                        .orElse(0L))
                .sum();
    }

    private static GameView view(Game game, Availability level) {
        return new GameView(
                game.sku(),
                game.title(),
                game.studio(),
                game.genre(),
                game.priceCents(),
                game.releasedOn(),
                game.cover(),
                game.blurb(),
                // A game the projection has never heard of reads as sold out rather than as
                // unlimited, which is the only safe direction for a number this service is guessing.
                level == null ? 0 : level.available(),
                level == null ? null : level.updatedAt());
    }

    /**
     * A game as the shop front sees it.
     *
     * @param sku the SKU
     * @param title its name
     * @param studio who made it
     * @param genre one word
     * @param priceCents price in minor units
     * @param releasedOn release date
     * @param cover artwork key
     * @param blurb the description
     * @param available what the storefront believes can still be bought — <b>a cache</b>, and till
     *     is what actually decides at checkout
     * @param availabilityAsOf the decision instant of the last event behind that number, or null if
     *     nothing has been heard about this SKU at all
     */
    record GameView(
            String sku,
            String title,
            String studio,
            String genre,
            long priceCents,
            LocalDate releasedOn,
            String cover,
            String blurb,
            long available,
            Instant availabilityAsOf) {}

    /**
     * @param items the games
     */
    record GamePage(List<GameView> items) {}

    /**
     * @param lines what to hold
     */
    record CheckoutRequest(@NotEmpty @Valid List<LineRequest> lines) {}

    /**
     * @param sku which game
     * @param quantity how many, at least one
     */
    record LineRequest(String sku, @Positive long quantity) {}

    /**
     * @param id the hold
     * @param expiresAt when it stops counting
     * @param totalCents what it would cost to pay for it
     */
    record Held(String id, Instant expiresAt, long totalCents) {}

    /**
     * @param id the hold
     * @param at when
     */
    record Paid(String id, Instant at) {}
}
