package io.till.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.till.client.TillApiException;
import io.till.client.TillClient;
import io.till.core.IdempotencyKey;
import io.till.core.Line;
import io.till.core.Outcome;
import io.till.core.RejectionCode;
import io.till.core.ReservationId;
import io.till.core.ReservationState;
import io.till.core.Sku;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

// JUnit does not inherit @ResourceLock from a superclass, so every class that shares the one
// database names the lock itself. Without it two Spring contexts write the same tables at once and
// each sees the other's rows.
@ResourceLock("till-database")
class ReservationApiTest extends ApiTestBase {

    @Test
    @DisplayName("the whole path over HTTP: stock in, held, sold")
    void theHappyPath() {
        TillClient till = client();
        admin().adjust(key("delivery-41"), sku("widget"), 100);

        Outcome.Reserved held =
                till.reserve(key("checkout-1"), List.of(Line.of("widget", 2)), Duration.ofMinutes(15));
        assertEquals(T0.plus(Duration.ofMinutes(15)), held.expiresAt(), "the deadline the caller was told");
        assertEquals(98, till.stock(sku("widget")).available());
        assertEquals(100, till.stock(sku("widget")).onHand(), "reserving does not move on-hand");

        till.commit(key("pay-1"), held.id());

        assertEquals(98, till.stock(sku("widget")).onHand(), "committing does");
        assertEquals(98, till.stock(sku("widget")).available());
        assertEquals(ReservationState.COMMITTED, till.reservation(held.id()).state());
    }

    @Test
    @DisplayName("the same key twice returns the same hold, not a second one")
    void replay() {
        TillClient till = client();
        admin().adjust(key("d1"), sku("widget"), 10);

        Outcome.Reserved first = till.reserve(key("checkout-8123"), List.of(Line.of("widget", 3)), null);
        Outcome.Reserved retry = till.reserve(key("checkout-8123"), List.of(Line.of("widget", 3)), null);

        assertEquals(first, retry, "byte for byte, including the id the server minted the first time");
        assertEquals(7, till.stock(sku("widget")).available(), "three units went, not six");
        assertEquals(1, ledger.allReservations().size());
    }

    @Test
    @DisplayName("a key reused for a different request is refused rather than served")
    void keyReuse() {
        TillClient till = client();
        admin().adjust(key("d1"), sku("widget"), 10);
        till.reserve(key("checkout-8123"), List.of(Line.of("widget", 3)), null);

        TillApiException thrown =
                assertThrows(
                        TillApiException.class,
                        () -> till.reserve(key("checkout-8123"), List.of(Line.of("widget", 9)), null));

        assertEquals(422, thrown.status());
        assertEquals(Optional.of(RejectionCode.IDEMPOTENCY_KEY_REUSED), thrown.rejection());
    }

    @Test
    @DisplayName("a refusal for stock is a 409 that says how far short every line fell")
    void insufficientStock() {
        TillClient till = client();
        admin().adjust(key("d1"), sku("widget"), 2);
        admin().adjust(key("d2"), sku("gadget"), 10);

        TillApiException thrown =
                assertThrows(
                        TillApiException.class,
                        () ->
                                till.reserve(
                                        key("c1"), List.of(Line.of("widget", 5), Line.of("gadget", 1)), null));

        assertEquals(409, thrown.status());
        assertEquals(Optional.of(RejectionCode.INSUFFICIENT_STOCK), thrown.rejection());
        assertEquals(
                List.of(new TillApiException.Shortfall("widget", 5, 2)),
                thrown.shortfalls(),
                "gadget was available, so it is not a shortfall");
        assertEquals(10, till.stock(sku("gadget")).available(), "and it was not taken either");
    }

    @Test
    @DisplayName("a hold past its deadline is a 410, and its stock is back")
    void expiredHoldIsGone() {
        TillClient till = client();
        admin().adjust(key("d1"), sku("widget"), 10);
        Outcome.Reserved held = till.reserve(key("c1"), List.of(Line.of("widget", 10)), Duration.ofMinutes(1));
        assertEquals(0, till.stock(sku("widget")).available());

        clock.advance(Duration.ofMinutes(2));

        // Nothing has swept. The deadline is what decides.
        assertEquals(
                ReservationState.EXPIRED,
                till.reservation(held.id()).effectiveState(),
                "and the stored state still says HELD, which is why both are reported");
        assertEquals(ReservationState.HELD, till.reservation(held.id()).state());

        TillApiException thrown =
                assertThrows(TillApiException.class, () -> till.commit(key("pay-1"), held.id()));
        assertEquals(410, thrown.status());
        assertEquals(Optional.of(RejectionCode.RESERVATION_EXPIRED), thrown.rejection());

        assertEquals(10, till.stock(sku("widget")).available(), "the refused commit gave the stock back");
    }

    @Test
    @DisplayName("expired stock is handed to the next caller without a sweep")
    void expiredStockIsReclaimedOnDemand() {
        TillClient till = client();
        admin().adjust(key("d1"), sku("widget"), 10);
        till.reserve(key("c1"), List.of(Line.of("widget", 10)), Duration.ofMinutes(1));

        clock.advance(Duration.ofMinutes(2));

        Outcome.Reserved second = till.reserve(key("c2"), List.of(Line.of("widget", 10)), null);
        assertEquals(10, second.lines().get(0).quantity());
    }

    @Test
    @DisplayName("releasing is idempotent by nature; committing is not")
    void releaseAndCommitDisagreeAboutTerminalStates() {
        TillClient till = client();
        admin().adjust(key("d1"), sku("widget"), 10);
        Outcome.Reserved held = till.reserve(key("c1"), List.of(Line.of("widget", 3)), null);

        till.release(key("cancel-1"), held.id());
        till.release(key("cancel-2"), held.id());
        assertEquals(10, till.stock(sku("widget")).available(), "released once, whatever the caller asked twice");

        TillApiException thrown =
                assertThrows(TillApiException.class, () -> till.commit(key("pay-1"), held.id()));
        assertEquals(409, thrown.status());
        assertEquals(Optional.of(RejectionCode.ALREADY_RELEASED), thrown.rejection());
    }

    @Test
    @DisplayName("committing twice is refused, because stock cannot leave twice")
    void doubleCommit() {
        TillClient till = client();
        admin().adjust(key("d1"), sku("widget"), 10);
        Outcome.Reserved held = till.reserve(key("c1"), List.of(Line.of("widget", 3)), null);
        till.commit(key("pay-1"), held.id());

        TillApiException thrown =
                assertThrows(TillApiException.class, () -> till.commit(key("pay-2"), held.id()));

        assertEquals(409, thrown.status());
        assertEquals(Optional.of(RejectionCode.ALREADY_COMMITTED), thrown.rejection());
        assertEquals(7, till.stock(sku("widget")).onHand());
    }

    @Test
    @DisplayName("a SKU that has never been stocked is a 404, not an out-of-stock answer")
    void unknownSku() {
        TillClient till = client();

        TillApiException reserve =
                assertThrows(
                        TillApiException.class, () -> till.reserve(key("c1"), List.of(Line.of("ghost", 1)), null));
        assertEquals(404, reserve.status());
        assertEquals(Optional.of(RejectionCode.UNKNOWN_SKU), reserve.rejection());

        TillApiException read = assertThrows(TillApiException.class, () -> till.stock(sku("ghost")));
        assertEquals(404, read.status());
    }

    @Test
    @DisplayName("a reservation that does not exist is a 404")
    void unknownReservation() {
        TillClient till = client();

        assertEquals(
                404,
                assertThrows(TillApiException.class, () -> till.reservation(rid("nope"))).status());
        assertEquals(
                404, assertThrows(TillApiException.class, () -> till.commit(key("k"), rid("nope"))).status());
    }

    @Test
    @DisplayName("a time to live beyond the configured maximum is a 400")
    void ttlIsBounded() {
        TillClient till = client();
        admin().adjust(key("d1"), sku("widget"), 10);

        TillApiException thrown =
                assertThrows(
                        TillApiException.class,
                        () -> till.reserve(key("c1"), List.of(Line.of("widget", 1)), Duration.ofDays(2)));

        assertEquals(400, thrown.status());
        assertTrue(thrown.getMessage().contains("ttlSeconds"), thrown.getMessage());
    }

    @Test
    @DisplayName("the default time to live is used when the caller does not say")
    void defaultTtl() {
        TillClient till = client();
        admin().adjust(key("d1"), sku("widget"), 10);

        Outcome.Reserved held = till.reserve(key("c1"), List.of(Line.of("widget", 1)), null);

        assertEquals(T0.plus(Duration.ofMinutes(15)), held.expiresAt());
    }

    @Test
    @DisplayName("stock cannot be written off below what is already reserved")
    void adjustRespectsHolds() {
        TillClient till = client();
        admin().adjust(key("d1"), sku("widget"), 10);
        till.reserve(key("c1"), List.of(Line.of("widget", 7)), null);

        TillApiException thrown =
                assertThrows(TillApiException.class, () -> admin().adjust(key("writeoff"), sku("widget"), -5));

        assertEquals(409, thrown.status());
        assertEquals(
                List.of(new TillApiException.Shortfall("widget", 5, 3)),
                thrown.shortfalls(),
                "three could have gone; seven are promised");

        // Three can.
        assertEquals(7, admin().adjust(key("writeoff-3"), sku("widget"), -3).onHand());
    }

    private static Sku sku(String value) {
        return Sku.of(value);
    }

    private static IdempotencyKey key(String value) {
        return IdempotencyKey.of(value);
    }

    private static ReservationId rid(String value) {
        return ReservationId.of(value);
    }
}
