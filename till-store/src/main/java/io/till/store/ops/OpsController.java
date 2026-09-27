package io.till.store.ops;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.till.client.TillClient;
import io.till.core.ReservationState;
import io.till.core.Sku;
import io.till.store.auth.Customer;
import io.till.store.catalogue.Game;
import io.till.store.catalogue.Games;
import io.till.store.ledger.LedgerKeys;
import io.till.store.ledger.OperatorLedger;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The operator console's backend: the ledger, for members of the admin group.
 *
 * <p>Access is decided by the security configuration — {@code /api/ops/**} requires the admin role —
 * not by anything in here, so there is no endpoint in this class that could forget to check.
 *
 * <p>Stock rows come back with the game's title beside the SKU. The ledger does not know titles and
 * should not; the console should not make an operator memorise SKUs either.
 */
@RestController
@RequestMapping("/api/ops")
@Tag(name = "ops", description = "The operator console. Admin group only.")
class OpsController {

    private static final int MAX_PAGE = 500;
    private static final long MAX_DELTA = 100_000;

    private final OperatorLedger ledger;
    private final Games games;

    OpsController(OperatorLedger ledger, Games games) {
        this.ledger = ledger;
        this.games = games;
    }

    @Operation(operationId = "opsStock", summary = "Stock levels, in SKU order")
    @GetMapping("/stock")
    StockPage stock(@RequestParam(defaultValue = "200") int limit, @RequestParam(required = false) String after) {
        TillClient.StockPage page = ledger.stock(Math.clamp(limit, 1, MAX_PAGE), after);
        Map<String, Game> titles = games.findAll(page.items().stream().map(s -> s.sku().value()).toList());
        return new StockPage(
                page.items().stream().map(s -> StockRow.of(s, titles.get(s.sku().value()))).toList(), page.nextAfter());
    }

    @Operation(operationId = "opsReservations", summary = "Reservations, newest first")
    @GetMapping("/reservations")
    ReservationPage reservations(
            @RequestParam(required = false) ReservationState state, @RequestParam(defaultValue = "100") int limit) {
        return new ReservationPage(ledger.reservations(state, Math.clamp(limit, 1, MAX_PAGE)).stream()
                .map(ReservationRow::of)
                .toList());
    }

    @Operation(operationId = "opsOutbox", summary = "The unpublished tail of the outbox")
    @GetMapping("/outbox")
    OutboxPage outbox(@RequestParam(defaultValue = "50") int limit) {
        TillClient.OutboxPage page = ledger.outbox(Math.clamp(limit, 1, MAX_PAGE));
        return new OutboxPage(
                page.backlog(),
                page.items().stream()
                        .map(i -> new OutboxRow(i.sequence(), i.dedupeKey(), i.recordedAt(), i.payload()))
                        .toList());
    }

    @Operation(operationId = "opsAdjust", summary = "Add or remove stock directly")
    @PostMapping("/stock/{sku}/adjust")
    StockRow adjust(
            @AuthenticationPrincipal OidcUser user,
            @PathVariable String sku,
            @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody Adjust body) {
        long delta = body.delta();
        if (delta == 0 || Math.abs(delta) > MAX_DELTA) {
            throw new IllegalArgumentException("delta must be non-zero and at most " + MAX_DELTA + " either way");
        }
        TillClient.StockView level = ledger.adjust(LedgerKeys.scoped("ops", Customer.id(user), sku, key), Sku.of(sku), delta);
        return StockRow.of(level, games.find(sku).orElse(null));
    }

    /**
     * @param delta units to add, negative to remove
     */
    @Schema(name = "OpsAdjust")
    record Adjust(@NotNull Long delta) {}

    /**
     * One stock level.
     *
     * @param sku the SKU
     * @param title the game's title, or null for a SKU the catalogue does not sell
     * @param onHand units physically held
     * @param reserved units held by open reservations
     * @param available what a new hold could take
     */
    @Schema(name = "OpsStock")
    record StockRow(String sku, @Schema(nullable = true) String title, long onHand, long reserved, long available) {

        static StockRow of(TillClient.StockView view, Game game) {
            return new StockRow(
                    view.sku().value(), game == null ? null : game.title(), view.onHand(), view.reserved(), view.available());
        }
    }

    /**
     * @param items stock levels in SKU order
     * @param nextAfter the cursor for the next page, or null at the end
     */
    @Schema(name = "OpsStockPage")
    record StockPage(List<StockRow> items, @Schema(nullable = true) String nextAfter) {}

    /**
     * One reservation.
     *
     * @param id the reservation
     * @param state as recorded
     * @param effectiveState as it stands now — a hold past its deadline is expired whether or not the
     *     expiry has been written down
     * @param lines what it holds
     * @param createdAt when it was taken
     * @param expiresAt when it stops counting
     */
    @Schema(name = "OpsReservation")
    record ReservationRow(
            String id,
            ReservationState state,
            ReservationState effectiveState,
            List<OpsLine> lines,
            Instant createdAt,
            Instant expiresAt) {

        static ReservationRow of(TillClient.ReservationView view) {
            return new ReservationRow(
                    view.id().value(),
                    view.state(),
                    view.effectiveState(),
                    view.lines().stream().map(l -> new OpsLine(l.sku().value(), l.quantity())).toList(),
                    view.createdAt(),
                    view.expiresAt());
        }
    }

    /**
     * @param sku which SKU
     * @param quantity how many
     */
    @Schema(name = "OpsLine")
    record OpsLine(String sku, long quantity) {}

    /**
     * @param items newest first
     */
    @Schema(name = "OpsReservationPage")
    record ReservationPage(List<ReservationRow> items) {}

    /**
     * @param sequence the ledger's ordering
     * @param dedupeKey what a consumer deduplicates on
     * @param recordedAt when it was written
     * @param payload the event, in the ledger's text form
     */
    @Schema(name = "OpsOutboxEntry")
    record OutboxRow(long sequence, String dedupeKey, Instant recordedAt, String payload) {}

    /**
     * @param backlog how many are waiting in total
     * @param items the oldest of them
     */
    @Schema(name = "OpsOutboxPage")
    record OutboxPage(long backlog, List<OutboxRow> items) {}
}
