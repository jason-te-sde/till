package io.till.server;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.till.core.Command;
import io.till.core.IdempotencyKey;
import io.till.core.Line;
import io.till.core.ReservationId;
import io.till.core.ReservationState;
import io.till.jdbc.JdbcLedger;
import jakarta.validation.Valid;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Holds on stock: taking them, keeping them, giving them back. */
@RestController
@RequestMapping("/v1/reservations")
class ReservationController {

    private final Commands commands;
    private final JdbcLedger ledger;
    private final Clock clock;
    private final TillProperties properties;

    ReservationController(Commands commands, JdbcLedger ledger, Clock clock, TillProperties properties) {
        this.commands = commands;
        this.ledger = ledger;
        this.clock = clock;
        this.properties = properties;
    }

    /**
     * Takes a hold.
     *
     * <p>The reservation id is minted here rather than sent by the caller, and that is what makes a
     * retry work: the same {@code Idempotency-Key} with a freshly minted id is still recognised as
     * the same request, and the answer carries the id of the hold that already exists.
     *
     * @param key the caller's key for this attempt
     * @param request what to hold and for how long
     * @return the hold
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(operationId = "reserve", summary = "Take a hold on stock")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "the hold was taken, or already existed under this key"),
        @ApiResponse(responseCode = "404", description = "a SKU has never been stocked"),
        @ApiResponse(responseCode = "409", description = "not enough available stock; the body lists every shortfall"),
        @ApiResponse(responseCode = "422", description = "this key was used for a different request"),
        @ApiResponse(responseCode = "503", description = "too much contention; retry")
    })
    Api.Reserved reserve(
            @Parameter(description = "Retrying with this key returns the first answer and changes nothing")
                    @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody Api.ReserveRequest request) {
        List<Line> lines = request.lines().stream().map(Api.LineRequest::toLine).toList();
        Command command =
                new Command.Reserve(
                        IdempotencyKey.of(key),
                        ReservationId.of(UUID.randomUUID().toString()),
                        lines,
                        ttlOf(request));
        return Api.Reserved.of(Outcomes.reserved(commands.run(command)));
    }

    /**
     * Turns a hold into a sale.
     *
     * @param key the caller's key for this attempt
     * @param id which hold
     * @return the commit
     */
    @PostMapping("/{id}/commit")
    @Operation(operationId = "commitReservation", summary = "Turn a hold into a sale")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "on-hand and reserved both went down"),
        @ApiResponse(responseCode = "404", description = "no such reservation"),
        @ApiResponse(responseCode = "409", description = "already committed, or already released"),
        @ApiResponse(responseCode = "410", description = "the hold ran out of time")
    })
    Api.Committed commit(@RequestHeader("Idempotency-Key") String key, @PathVariable String id) {
        return Api.Committed.of(
                Outcomes.committed(commands.run(new Command.Commit(IdempotencyKey.of(key), ReservationId.of(id)))));
    }

    /**
     * Gives a hold back.
     *
     * <p>Succeeds on a hold that was already released or has expired, because the caller asked for it
     * to be gone and it is gone. Refused only on one that was committed.
     *
     * @param key the caller's key for this attempt
     * @param id which hold
     * @return the release
     */
    @PostMapping("/{id}/release")
    @Operation(operationId = "releaseReservation", summary = "Give a hold back")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "the hold is gone, whether it went now or earlier"),
        @ApiResponse(responseCode = "404", description = "no such reservation"),
        @ApiResponse(responseCode = "409", description = "it was committed and cannot be released")
    })
    Api.Released release(@RequestHeader("Idempotency-Key") String key, @PathVariable String id) {
        return Api.Released.of(
                Outcomes.released(commands.run(new Command.Release(IdempotencyKey.of(key), ReservationId.of(id)))));
    }

    /**
     * Lists reservations, newest first.
     *
     * @param state only this state, or null for all
     * @param limit how many at most; clamped by the ledger rather than trusted
     * @return the page
     */
    @GetMapping
    @Operation(operationId = "listReservations", summary = "List reservations, newest first")
    Api.ReservationPage list(
            @RequestParam(required = false) ReservationState state,
            @RequestParam(defaultValue = "100") int limit) {
        return Api.ReservationPage.of(
                ledger.listReservations(Optional.ofNullable(state), limit), clock.instant());
    }

    /**
     * Looks a hold up.
     *
     * @param id which hold
     * @return its state, including what its state effectively is right now
     */
    @GetMapping("/{id}")
    @Operation(operationId = "getReservation", summary = "Look a hold up")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "the reservation"),
        @ApiResponse(responseCode = "404", description = "no such reservation")
    })
    Api.ReservationView get(@PathVariable String id) {
        return ledger.reservation(ReservationId.of(id))
                .map(reservation -> Api.ReservationView.of(reservation, clock.instant()))
                .orElseThrow(() -> new NotFoundException("No such reservation", "no reservation " + id));
    }

    private Duration ttlOf(Api.ReserveRequest request) {
        Duration ttl =
                request.ttlSeconds() == null ? properties.defaultTtl() : Duration.ofSeconds(request.ttlSeconds());
        if (ttl.compareTo(properties.maxTtl()) > 0) {
            throw new IllegalArgumentException(
                    "ttlSeconds must be at most " + properties.maxTtl().toSeconds() + ", got " + ttl.toSeconds());
        }
        return ttl;
    }
}
