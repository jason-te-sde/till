package io.till.server;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.till.core.Command;
import io.till.core.IdempotencyKey;
import io.till.core.LedgerInspector;
import io.till.core.Sku;
import io.till.core.StockItem;
import io.till.jdbc.JdbcLedger;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Optional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Stock levels, and the one operation that changes them without a reservation behind it. */
@RestController
@RequestMapping("/v1/stock")
class StockController {

    private final Commands commands;
    private final JdbcLedger ledger;

    StockController(Commands commands, JdbcLedger ledger) {
        this.commands = commands;
        this.ledger = ledger;
    }

    /**
     * Lists levels, a page at a time.
     *
     * @param limit how many at most; clamped by the ledger rather than trusted
     * @param after the last SKU of the previous page, or null to start
     * @return the page and a cursor for the next one
     */
    @GetMapping
    @Operation(operationId = "listStock", summary = "List stock levels")
    Api.StockPage list(
            @RequestParam(defaultValue = "100") int limit, @RequestParam(required = false) String after) {
        List<StockItem> items = ledger.listStock(Optional.ofNullable(after).map(Sku::of), limit);
        return Api.StockPage.of(items, Math.min(limit, LedgerInspector.MAX_PAGE));
    }

    /**
     * Reads a level.
     *
     * @param sku which SKU
     * @return on-hand, reserved and available
     */
    @GetMapping("/{sku}")
    @Operation(operationId = "getStock", summary = "Read a stock level")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "the level"),
        @ApiResponse(responseCode = "404", description = "this SKU has never been stocked")
    })
    Api.Stock get(@PathVariable String sku) {
        return ledger.stock(Sku.of(sku))
                .map(Api.Stock::of)
                .orElseThrow(
                        () ->
                                new NotFoundException(
                                        "Unknown SKU", "no stock has ever been recorded for " + sku));
    }

    /**
     * Changes on-hand stock: a delivery arriving, a breakage written off, a SKU created.
     *
     * <p>Needs the admin token. Refused if it would take on-hand below what is already reserved,
     * because those units are promised to somebody.
     *
     * @param key the caller's key for this attempt
     * @param sku which SKU
     * @param request how much to add or remove
     * @return the new level
     */
    @PostMapping("/{sku}/adjust")
    @Operation(operationId = "adjustStock", summary = "Change on-hand stock directly")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "the new level"),
        @ApiResponse(responseCode = "403", description = "this needs the admin token"),
        @ApiResponse(responseCode = "404", description = "removing stock from a SKU that has no row"),
        @ApiResponse(responseCode = "409", description = "this would take on-hand below what is reserved")
    })
    Api.Stock adjust(
            @RequestHeader("Idempotency-Key") String key,
            @PathVariable String sku,
            @Valid @RequestBody Api.AdjustRequest request) {
        return Api.Stock.of(
                Outcomes.adjusted(
                        commands.run(new Command.Adjust(IdempotencyKey.of(key), Sku.of(sku), request.delta()))));
    }
}
