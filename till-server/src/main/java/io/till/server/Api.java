package io.till.server;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import io.till.core.Codec;
import io.till.core.Line;
import io.till.core.OutboxEntry;
import io.till.core.Outcome;
import io.till.core.Reservation;
import io.till.core.Sku;
import io.till.core.StockItem;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Instant;
import java.util.List;

/**
 * The wire types.
 *
 * <p>Separate from the kernel's types on purpose. The API is a contract with clients that outlives
 * any one refactor of the rules, and a DTO that is the domain type is a domain type that cannot be
 * renamed without a version bump.
 *
 * <p>Response fields carry {@code @Schema(requiredMode = REQUIRED)} one by one, which is tedious and
 * is the only way to say it. A record component is non-null by construction — several of these are
 * primitives and cannot be null at all — and springdoc does not infer any of that, so without the
 * annotation the published contract says every field is optional. A generated client then types them
 * all as possibly-undefined, and every call site grows a check for a case that cannot happen.
 */
final class Api {

    private Api() {}

    /**
     * A request to take a hold.
     *
     * @param lines what to hold; at least one, no SKU twice
     * @param ttlSeconds how long the hold lasts, or null for the server default
     */
    @Schema(name = "ReserveRequest")
    record ReserveRequest(
            @NotEmpty @Valid List<LineRequest> lines,
            @Schema(description = "How long the hold lasts. Defaults to till.default-ttl.", example = "900")
                    @Positive Long ttlSeconds) {}

    /**
     * One SKU and a quantity.
     *
     * @param sku the SKU
     * @param quantity how many, at least 1
     */
    @Schema(name = "Line")
    record LineRequest(
            @NotNull @Schema(requiredMode = REQUIRED) String sku,
            @Positive @Schema(requiredMode = REQUIRED) long quantity) {

        Line toLine() {
            return new Line(Sku.of(sku), quantity);
        }

        static LineRequest of(Line line) {
            return new LineRequest(line.sku().value(), line.quantity());
        }
    }

    /**
     * A hold that was taken.
     *
     * @param id the hold's identifier, which the caller uses to commit or release it
     * @param lines what is held
     * @param expiresAt when the hold stops counting unless committed
     */
    @Schema(name = "Reserved")
    record Reserved(
            @Schema(requiredMode = REQUIRED) String id,
            @Schema(requiredMode = REQUIRED) List<LineRequest> lines,
            @Schema(requiredMode = REQUIRED) Instant expiresAt) {

        static Reserved of(Outcome.Reserved outcome) {
            return new Reserved(
                    outcome.id().value(), outcome.lines().stream().map(LineRequest::of).toList(), outcome.expiresAt());
        }
    }

    /**
     * A hold that became a sale.
     *
     * @param id the hold
     * @param committedAt when
     */
    @Schema(name = "Committed")
    record Committed(
            @Schema(requiredMode = REQUIRED) String id,
            @Schema(requiredMode = REQUIRED) Instant committedAt) {

        static Committed of(Outcome.Committed outcome) {
            return new Committed(outcome.id().value(), outcome.at());
        }
    }

    /**
     * A hold that was given back.
     *
     * @param id the hold
     * @param releasedAt when
     */
    @Schema(name = "Released")
    record Released(
            @Schema(requiredMode = REQUIRED) String id,
            @Schema(requiredMode = REQUIRED) Instant releasedAt) {

        static Released of(Outcome.Released outcome) {
            return new Released(outcome.id().value(), outcome.at());
        }
    }

    /**
     * The full state of one reservation.
     *
     * @param id the hold
     * @param state HELD, COMMITTED, RELEASED or EXPIRED
     * @param effectiveState what the state is <i>now</i>: HELD becomes EXPIRED once the deadline has
     *     passed, whether or not anything has written that down yet
     * @param lines what it holds
     * @param createdAt when it was taken
     * @param expiresAt when it stops counting
     */
    @Schema(name = "Reservation")
    record ReservationView(
            @Schema(requiredMode = REQUIRED) String id,
            @Schema(requiredMode = REQUIRED, allowableValues = {"HELD", "COMMITTED", "RELEASED", "EXPIRED"})
                    String state,
            @Schema(requiredMode = REQUIRED, allowableValues = {"HELD", "COMMITTED", "RELEASED", "EXPIRED"})
                    String effectiveState,
            @Schema(requiredMode = REQUIRED) List<LineRequest> lines,
            @Schema(requiredMode = REQUIRED) Instant createdAt,
            @Schema(requiredMode = REQUIRED) Instant expiresAt) {

        static ReservationView of(Reservation reservation, Instant now) {
            return new ReservationView(
                    reservation.id().value(),
                    reservation.state().name(),
                    reservation.effectiveState(now).name(),
                    reservation.lines().stream().map(LineRequest::of).toList(),
                    reservation.createdAt(),
                    reservation.expiresAt());
        }
    }

    /**
     * A stock level.
     *
     * @param sku the SKU
     * @param onHand units physically held
     * @param reserved units spoken for by open holds
     * @param available what a new hold may take
     */
    @Schema(name = "Stock")
    record Stock(
            @Schema(requiredMode = REQUIRED) String sku,
            @Schema(requiredMode = REQUIRED) long onHand,
            @Schema(requiredMode = REQUIRED) long reserved,
            @Schema(requiredMode = REQUIRED) long available) {

        static Stock of(StockItem item) {
            return new Stock(item.sku().value(), item.onHand(), item.reserved(), item.available());
        }

        static Stock of(Outcome.Adjusted outcome) {
            return new Stock(
                    outcome.sku().value(), outcome.onHand(), outcome.reserved(), outcome.available());
        }
    }

    /**
     * A page of stock levels.
     *
     * @param items the levels, in SKU order
     * @param nextAfter pass this back as {@code after} for the next page, or null at the end. A
     *     cursor rather than an offset, so a row inserted during a walk cannot shift a later page
     */
    @Schema(name = "StockPage")
    record StockPage(
            @Schema(requiredMode = REQUIRED) List<Stock> items,
            @Schema(description = "Absent on the last page.") String nextAfter) {

        static StockPage of(List<StockItem> items, int limit) {
            List<Stock> page = items.stream().map(Stock::of).toList();
            // Only offer a cursor when the page was full. A short page is the end of the data, and a
            // cursor there would make a client ask one more time for nothing.
            String next = page.size() == limit && !page.isEmpty() ? page.get(page.size() - 1).sku() : null;
            return new StockPage(page, next);
        }
    }

    /**
     * A page of reservations, newest first.
     *
     * @param items the reservations
     */
    @Schema(name = "ReservationPage")
    record ReservationPage(@Schema(requiredMode = REQUIRED) List<ReservationView> items) {

        static ReservationPage of(List<Reservation> reservations, Instant now) {
            return new ReservationPage(
                    reservations.stream().map(reservation -> ReservationView.of(reservation, now)).toList());
        }
    }

    /**
     * One event waiting to be published.
     *
     * @param sequence the ledger's ordering
     * @param dedupeKey what a consumer deduplicates on
     * @param recordedAt when the row was written
     * @param payload the event, in the on-disk text form
     */
    @Schema(name = "OutboxEntry")
    record OutboxEntryView(
            @Schema(requiredMode = REQUIRED) long sequence,
            @Schema(requiredMode = REQUIRED) String dedupeKey,
            @Schema(requiredMode = REQUIRED) Instant recordedAt,
            @Schema(requiredMode = REQUIRED) String payload) {

        static OutboxEntryView of(OutboxEntry entry) {
            return new OutboxEntryView(
                    entry.sequence(), entry.dedupeKey(), entry.recordedAt(), Codec.encodeEvent(entry.event()));
        }
    }

    /**
     * The unpublished tail of the outbox.
     *
     * @param backlog how many are waiting in total, which is the number to alert on
     * @param items the oldest of them
     */
    @Schema(name = "OutboxPage")
    record OutboxPage(
            @Schema(requiredMode = REQUIRED) long backlog,
            @Schema(requiredMode = REQUIRED) List<OutboxEntryView> items) {}

    /**
     * A direct change to on-hand stock.
     *
     * @param delta units to add, negative to remove, never zero
     */
    @Schema(name = "AdjustRequest")
    record AdjustRequest(@NotNull Long delta) {}

    /**
     * What every failure looks like.
     *
     * <p>Declared as a record so that it appears in the published contract and a generated client
     * gets a type for it, but <b>never instantiated</b>: the thing actually serialised is Spring's
     * {@code ProblemDetail}, assembled in {@link Problems}. Two declarations of one shape is a thing
     * that can drift, so {@code OpenApiContractTest} sends a real failing request and checks the body
     * against this.
     *
     * <p>RFC 9457, plus three extensions. {@code code} exists because the interesting distinctions
     * live <i>inside</i> one status — a 409 is "somebody got there first" or "you already paid for
     * this", and a client that can only see {@code 409} shows the same sentence for both.
     *
     * @param type {@code about:blank} when present, and <b>absent</b> from most bodies: RFC 9457
     *     makes {@code about:blank} the default and Spring omits a field that would only repeat it.
     *     So it is declared optional, which is a statement about the wire and not a preference
     * @param title a short name for the class of failure, stable enough to log but meant for people
     * @param status the HTTP status, repeated in the body so a stored problem is self-contained
     * @param detail one sentence naming the specific thing that went wrong
     * @param instance absent here
     * @param code the machine-readable reason, when there is one. Mostly a {@code RejectionCode},
     *     plus {@code CONTENTION} from the retry loop and {@code UNAUTHORIZED}/{@code FORBIDDEN} from
     *     the authentication filter, which runs before any of the kernel's vocabulary applies
     * @param requestId the {@code X-Request-Id} of the request that failed, so a screenshot of an
     *     error is enough to find the log lines
     * @param shortfalls present only on {@code INSUFFICIENT_STOCK}: how far short each SKU fell, so a
     *     client can offer a smaller basket without another round trip
     */
    @Schema(name = "Problem", description = "An RFC 9457 problem detail.")
    record Problem(
            @Schema(example = "about:blank") String type,
            @Schema(requiredMode = REQUIRED, example = "Not enough stock") String title,
            @Schema(requiredMode = REQUIRED, example = "409") int status,
            @Schema(requiredMode = REQUIRED, example = "widget: asked for 5, 2 available") String detail,
            String instance,
            @Schema(
                            example = "INSUFFICIENT_STOCK",
                            allowableValues = {
                                "INSUFFICIENT_STOCK",
                                "UNKNOWN_SKU",
                                "RESERVATION_NOT_FOUND",
                                "RESERVATION_EXPIRED",
                                "ALREADY_COMMITTED",
                                "ALREADY_RELEASED",
                                "RESERVATION_ID_IN_USE",
                                "IDEMPOTENCY_KEY_REUSED",
                                "CONTENTION",
                                "UNAUTHORIZED",
                                "FORBIDDEN"
                            })
                    String code,
            @Schema(example = "0f9c1a7e-1f3a-4a2b-9a1e-2c4d6e8f0a11") String requestId,
            List<Shortfall> shortfalls) {}

    /**
     * How far short one SKU fell.
     *
     * @param sku which SKU
     * @param requested units asked for
     * @param available units that could have been taken
     */
    @Schema(name = "Shortfall")
    record Shortfall(
            @Schema(requiredMode = REQUIRED) String sku,
            @Schema(requiredMode = REQUIRED) long requested,
            @Schema(requiredMode = REQUIRED) long available) {}
}
