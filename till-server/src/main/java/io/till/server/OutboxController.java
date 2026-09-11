package io.till.server;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.till.core.LedgerInspector;
import io.till.core.OutboxEntry;
import io.till.jdbc.JdbcLedger;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The unpublished tail of the outbox, for an operator looking at why a consumer is behind.
 *
 * <p>Behind the admin token. The backlog is not a secret, but the payloads are the full history of
 * what moved and when, which is more than a checkout service has any reason to read.
 */
@RestController
@RequestMapping("/v1/outbox")
class OutboxController {

    private final JdbcLedger ledger;

    OutboxController(JdbcLedger ledger) {
        this.ledger = ledger;
    }

    /**
     * The oldest unpublished events, and how many there are altogether.
     *
     * @param limit how many entries to return
     * @return the backlog and a page of it
     */
    @GetMapping
    @Operation(operationId = "listOutbox", summary = "The unpublished tail of the outbox")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "the backlog and its oldest entries"),
        @ApiResponse(responseCode = "403", description = "this needs the admin token")
    })
    Api.OutboxPage list(@RequestParam(defaultValue = "50") int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be at least 1, got " + limit);
        }
        List<OutboxEntry> entries = ledger.unpublished(Math.min(limit, LedgerInspector.MAX_PAGE));
        // The backlog is counted rather than derived from the page, because the page is capped and
        // "50 waiting" when 50 is the cap is exactly the case an operator needs the real number for.
        return new Api.OutboxPage(
                ledger.backlog(), entries.stream().map(Api.OutboxEntryView::of).toList());
    }
}
