package io.till.core;

import static io.till.core.Fixtures.T0;
import static io.till.core.Fixtures.TTL;
import static io.till.core.Fixtures.key;
import static io.till.core.Fixtures.line;
import static io.till.core.Fixtures.rid;
import static io.till.core.Fixtures.sku;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class KernelIdempotencyTest {

    private static final Command.Reserve RESERVE =
            new Command.Reserve(key("checkout-8123"), rid("r1"), List.of(line("widget", 3)), TTL);

    @Test
    @DisplayName("a key that has been used returns what it returned before, and writes nothing")
    void replayReturnsTheRecordedOutcome() {
        Outcome original = new Outcome.Reserved(rid("r1"), List.of(line("widget", 3)), T0.plus(TTL));
        Snapshot snapshot =
                Fixtures.snapshot()
                        .stock(sku("widget"), 10, 3, 1)
                        .recordedOutcome(record(RESERVE, original))
                        .build();

        // Ten minutes later, from a client that never saw the first response.
        Decision decision = Kernel.decide(snapshot, RESERVE, T0.plusSeconds(600));

        assertEquals(original, decision.outcome());
        assertFalse(decision.writes(), "a replay changes nothing, so there is nothing to conflict on");
    }

    @Test
    @DisplayName("a replay reproduces the original reservation id, not a new one")
    void replayKeepsTheOriginalIdentity() {
        Outcome original = new Outcome.Reserved(rid("r1"), List.of(line("widget", 3)), T0.plus(TTL));
        Snapshot snapshot =
                Fixtures.snapshot().stock(sku("widget"), 10, 3, 1).recordedOutcome(record(RESERVE, original)).build();

        // The server minted a new id for the retry, as a stateless server must.
        Command.Reserve retry =
                new Command.Reserve(key("checkout-8123"), rid("r2"), List.of(line("widget", 3)), TTL);
        Decision decision = Kernel.decide(snapshot, retry, T0.plusSeconds(600));

        assertEquals(original, decision.outcome());
        assertEquals(
                rid("r1"),
                ((Outcome.Reserved) decision.outcome()).id(),
                "the caller is told about the hold it already has, not the one it just asked for");
    }

    @Test
    @DisplayName("the reservation id is not part of what makes a reserve request that request")
    void fingerprintIgnoresTheReservationId() {
        Command.Reserve one = RESERVE;
        Command.Reserve other =
                new Command.Reserve(key("checkout-8123"), rid("different"), List.of(line("widget", 3)), TTL);

        // Without this, a stateless server minting an id per request would reject every retry it
        // ever received as a key reuse.
        assertEquals(one.fingerprint(), other.fingerprint());
    }

    @Test
    @DisplayName("a key reused for a different request is refused rather than served")
    void reuseIsRefused() {
        Outcome original = new Outcome.Reserved(rid("r1"), List.of(line("widget", 3)), T0.plus(TTL));
        Snapshot snapshot =
                Fixtures.snapshot().stock(sku("widget"), 10, 3, 1).recordedOutcome(record(RESERVE, original)).build();

        Command.Reserve different =
                new Command.Reserve(key("checkout-8123"), rid("r9"), List.of(line("widget", 99)), TTL);
        Decision decision = Kernel.decide(snapshot, different, T0.plusSeconds(600));

        assertEquals(RejectionCode.IDEMPOTENCY_KEY_REUSED, Fixtures.rejection(decision).code());
        assertTrue(
                decision.outcomeRecord().isEmpty(),
                "recording this would make the mistake permanent and refuse the real request forever");
        assertFalse(decision.writes());
    }

    @Test
    @DisplayName("a different quantity is a different request")
    void quantityIsPartOfTheFingerprint() {
        Command.Reserve two = new Command.Reserve(key("k"), rid("r"), List.of(line("widget", 2)), TTL);
        Command.Reserve three = new Command.Reserve(key("k"), rid("r"), List.of(line("widget", 3)), TTL);

        assertNotEquals(two.fingerprint(), three.fingerprint());
    }

    @Test
    @DisplayName("a different time to live is a different request")
    void ttlIsPartOfTheFingerprint() {
        Command.Reserve quarter =
                new Command.Reserve(key("k"), rid("r"), List.of(line("widget", 2)), Duration.ofMinutes(15));
        Command.Reserve hour =
                new Command.Reserve(key("k"), rid("r"), List.of(line("widget", 2)), Duration.ofHours(1));

        assertNotEquals(quarter.fingerprint(), hour.fingerprint());
    }

    @Test
    @DisplayName("commands of different kinds never share a fingerprint")
    void kindIsPartOfTheFingerprint() {
        assertNotEquals(
                new Command.Commit(key("k"), rid("r1")).fingerprint(),
                new Command.Release(key("k"), rid("r1")).fingerprint());
    }

    @Test
    @DisplayName("a rejection is recorded, so a retry is told the same no")
    void rejectionsAreRecorded() {
        Snapshot snapshot = Fixtures.snapshot().stock(sku("widget"), 1, 0, 0).build();

        Decision decision = Kernel.decide(snapshot, RESERVE, T0);

        assertEquals(RejectionCode.INSUFFICIENT_STOCK, Fixtures.rejection(decision).code());
        assertTrue(
                decision.outcomeRecord().isPresent(),
                "a client that retried a timed-out request must not be told 'no stock' and then 'created'");
        assertEquals(
                decision.outcome(), decision.outcomeRecord().orElseThrow().outcome(), "and the same no, exactly");
    }

    @Test
    @DisplayName("a recorded outcome survives a round trip through storage unchanged")
    void recordedOutcomesRoundTrip() {
        Snapshot snapshot = Fixtures.snapshot().stock(sku("widget"), 10, 0, 0).build();

        Decision decision = Kernel.decide(snapshot, RESERVE, T0);
        OutcomeRecord stored = decision.outcomeRecord().orElseThrow();

        assertEquals(decision.outcome(), stored.outcome());
        assertEquals(RESERVE.fingerprint(), stored.fingerprint());
    }

    private static OutcomeRecord record(Command command, Outcome outcome) {
        return OutcomeRecord.of(command, outcome, T0);
    }
}
