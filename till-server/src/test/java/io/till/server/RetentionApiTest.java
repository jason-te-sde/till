package io.till.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.till.client.TillApiException;
import io.till.client.TillClient;
import io.till.core.IdempotencyKey;
import io.till.core.Line;
import io.till.core.OutboxEntry;
import io.till.core.Outcome;
import io.till.core.ReservationState;
import io.till.core.Sku;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * The job that deletes things.
 *
 * <p>Held to a higher bar than the rest, because every other component here can be wrong and be
 * fixed. This one is wrong once and the rows are gone.
 */
// Suites that share the one database run one at a time; see the note in the other API tests.
@ResourceLock("till-database")
@TestPropertySource(
        properties = {
            // Driven by hand, so no test waits for a scheduler.
            "till.retention.interval=1h",
            "till.retention.idempotency=7d",
            "till.retention.outbox=30d",
            "till.retention.reservations=0s",
            "till.outbox.enabled=false"
        })
class RetentionApiTest extends ApiTestBase {

    @Autowired
    RetentionSweeper retention;

    @Test
    @DisplayName("an idempotency record older than the window is forgotten, and a recent one is not")
    void forgetsOldKeys() {
        TillClient till = admin();
        till.adjust(IdempotencyKey.of("old-delivery"), Sku.of("widget"), 100);

        // Published, which is what lets the key go at all — see keepsAKeyWhoseEventIsStillQueued
        // for why. The publisher is off in this suite, so this stands in for it.
        ledger.markPublished(ledger.unpublished(10).stream().map(OutboxEntry::sequence).toList(), clock.instant());

        // Past both windows, so this pass prunes the outbox row and then forgets the key.
        clock.advance(Duration.ofDays(31));
        till.adjust(IdempotencyKey.of("recent-delivery"), Sku.of("widget"), 1);

        retention.prune();

        // The old key is gone, so sending it again executes rather than replaying. That is the whole
        // risk of this job, and it is why the default window is seven days rather than seven hours.
        assertEquals(
                201,
                till.adjust(IdempotencyKey.of("old-delivery"), Sku.of("widget"), 100).onHand(),
                "100 + 1 + 100: the old key was forgotten and ran again");

        // The recent one still replays — and replaying returns the answer that was recorded at the
        // time, 101, not a fresh reading of 201. Those two numbers differing is the point: it proves
        // the reply came out of the idempotency record rather than from running the command again.
        assertEquals(
                101,
                till.adjust(IdempotencyKey.of("recent-delivery"), Sku.of("widget"), 1).onHand(),
                "the recent key replayed its recorded answer rather than adding another one");
    }

    @Test
    @DisplayName("a key is kept while its own event is still in the outbox, however old it is")
    void keepsAKeyWhoseEventIsStillQueued() {
        TillClient till = admin();
        till.adjust(IdempotencyKey.of("old-delivery"), Sku.of("widget"), 100);

        // Far past every window, but the event was never published, so the outbox row is still
        // there — and its deduplication key is "adjusted:old-delivery".
        clock.advance(Duration.ofDays(400));
        retention.prune();

        // Forgetting the key here would let this command run a second time and write an event whose
        // key already exists. The insert would conflict, the decision could not be applied, and this
        // caller would get 503 on every attempt, for good. So the key stays.
        assertEquals(
                100,
                till.adjust(IdempotencyKey.of("old-delivery"), Sku.of("widget"), 100).onHand(),
                "the key was kept, so this replayed rather than adding another 100");
    }

    @Test
    @DisplayName("an unpublished outbox row is never deleted, however old it is")
    void keepsUnpublishedEvents() {
        admin().adjust(IdempotencyKey.of("d1"), Sku.of("widget"), 10);
        assertEquals(1, ledger.backlog());

        clock.advance(Duration.ofDays(400));
        retention.prune();

        // An unpublished row is a change nothing downstream has heard about. Deleting one loses it
        // for good, and no retention window makes that acceptable.
        assertEquals(1, ledger.backlog());
        assertEquals(1, ledger.allEvents().size());
    }

    @Test
    @DisplayName("a published outbox row past the window goes")
    void prunesPublishedEvents() {
        admin().adjust(IdempotencyKey.of("d1"), Sku.of("widget"), 10);
        ledger.markPublished(ledger.unpublished(10).stream().map(OutboxEntry::sequence).toList(), clock.instant());
        assertEquals(1, ledger.allEvents().size());

        clock.advance(Duration.ofDays(31));
        retention.prune();

        assertTrue(ledger.allEvents().isEmpty());
    }

    @Test
    @DisplayName("a held reservation is never deleted, because its units are still counted")
    void neverDeletesAHeldReservation() {
        TillClient till = admin();
        till.adjust(IdempotencyKey.of("d1"), Sku.of("widget"), 10);
        Outcome.Reserved held = till.reserve(IdempotencyKey.of("c1"), java.util.List.of(Line.of("widget", 4)), null);

        clock.advance(Duration.ofDays(400));
        // Reservation retention is off by default, but even with it on this row must survive: its
        // four units are in till_stock.reserved, and deleting the row without lowering that counter
        // leaks the stock permanently.
        ledger.pruneReservations(clock.instant(), 100);

        assertEquals(ReservationState.HELD, till.reservation(held.id()).state());
        assertEquals(6, till.stock(Sku.of("widget")).available());
    }

    @Test
    @DisplayName("finished reservations are kept forever unless somebody asks otherwise")
    void reservationsAreKeptByDefault() {
        TillClient till = admin();
        till.adjust(IdempotencyKey.of("d1"), Sku.of("widget"), 10);
        Outcome.Reserved held = till.reserve(IdempotencyKey.of("c1"), java.util.List.of(Line.of("widget", 1)), null);
        till.commit(IdempotencyKey.of("pay-1"), held.id());

        clock.advance(Duration.ofDays(3650));
        retention.prune();

        assertEquals(ReservationState.COMMITTED, till.reservation(held.id()).state());
    }

    @Test
    @DisplayName("deleting in batches leaves nothing behind, however many passes it takes")
    void deletesEverythingEligible() {
        TillClient till = admin();
        for (int i = 0; i < 25; i++) {
            till.adjust(IdempotencyKey.of("d-" + i), Sku.of("widget"), 1);
        }
        ledger.markPublished(ledger.unpublished(100).stream().map(OutboxEntry::sequence).toList(), clock.instant());

        clock.advance(Duration.ofDays(40));
        // A batch far smaller than the number of rows, so the loop has to come round several times.
        int deleted = 0;
        for (int pass = 0; pass < 20; pass++) {
            deleted += ledger.pruneOutbox(clock.instant(), 4);
        }

        assertEquals(25, deleted);
        assertTrue(ledger.allEvents().isEmpty());
    }

    @Test
    @DisplayName("a nonsense batch size is refused rather than deleting everything")
    void batchSizeIsValidated() {
        assertThrows(IllegalArgumentException.class, () -> ledger.pruneOutbox(clock.instant(), 0));
        assertThrows(IllegalArgumentException.class, () -> ledger.forgetIdempotency(clock.instant(), -1));
    }

    @Test
    @DisplayName("a window of zero means keep forever, not delete everything")
    void zeroMeansForever() {
        admin().adjust(IdempotencyKey.of("d1"), Sku.of("widget"), 10);
        ledger.markPublished(ledger.unpublished(10).stream().map(OutboxEntry::sequence).toList(), clock.instant());
        clock.advance(Duration.ofDays(400));

        // Reservations are configured to 0s in this context, and the sweep leaves them alone. The
        // opposite reading — zero means "older than now", so delete everything — is the mistake this
        // asserts against.
        TillClient till = admin();
        Outcome.Reserved held = till.reserve(IdempotencyKey.of("c1"), java.util.List.of(Line.of("widget", 1)), null);
        till.release(IdempotencyKey.of("cancel-1"), held.id());

        retention.prune();

        assertEquals(ReservationState.RELEASED, till.reservation(held.id()).state());
    }
}
