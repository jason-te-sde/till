package io.till.store.orders;

import io.swagger.v3.oas.annotations.media.Schema;
import io.till.store.catalogue.Game;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** What the order endpoints take and return. */
public final class OrderViews {

    private OrderViews() {}

    /**
     * A basket, as sent to place an order. No prices: those are read from the catalogue on the server,
     * because a price a browser sends is a price a browser can change.
     *
     * @param lines one per game
     */
    @Schema(name = "PlaceOrder")
    public record PlaceOrder(@NotEmpty @Size(max = 20) @Valid List<LineRequest> lines) {}

    /**
     * @param sku which game
     * @param quantity how many, one to ten
     */
    @Schema(name = "LineRequest")
    public record LineRequest(@NotBlank String sku, @Min(1) @Max(10) int quantity) {}

    /**
     * An order.
     *
     * @param id the order
     * @param status where it stands <i>now</i> — an unpaid order past its deadline reads as expired even
     *     before the ledger's expiry event has arrived
     * @param createdAt when it was placed
     * @param expiresAt when the hold runs out unless paid for
     * @param closedAt when it stopped being pending, or null
     * @param totalCents the total, fixed when placed
     * @param currency ISO 4217
     * @param lines what it is for
     */
    @Schema(name = "Order")
    public record OrderView(
            UUID id,
            Order.Status status,
            Instant createdAt,
            Instant expiresAt,
            @Schema(nullable = true) Instant closedAt,
            long totalCents,
            String currency,
            List<OrderLineView> lines) {

        static OrderView of(Order order, Instant now, Map<String, Game> catalogue) {
            return new OrderView(
                    order.id(),
                    order.effectiveStatus(now),
                    order.createdAt(),
                    order.expiresAt(),
                    order.closedAt(),
                    order.totalCents(),
                    order.currency(),
                    order.lines().stream()
                            .map(line -> new OrderLineView(
                                    line.sku(),
                                    line.title(),
                                    line.unitPriceCents(),
                                    line.quantity(),
                                    catalogue.containsKey(line.sku()) ? catalogue.get(line.sku()).cover() : null))
                            .toList());
        }
    }

    /**
     * One game on an order.
     *
     * @param sku which game
     * @param title its name when bought
     * @param unitPriceCents its price when bought
     * @param quantity how many
     * @param cover artwork motif, or null if the game has since left the catalogue
     */
    @Schema(name = "OrderLine")
    public record OrderLineView(
            String sku, String title, long unitPriceCents, int quantity, @Schema(nullable = true) String cover) {}

    /**
     * @param items most recent first
     */
    @Schema(name = "OrderPage")
    public record OrderPage(List<OrderView> items) {}
}
