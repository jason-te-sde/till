package io.till.testkit;

import io.till.core.Command;
import io.till.core.IdempotencyKey;
import io.till.core.LedgerInspector;
import io.till.core.Outcome;
import io.till.core.Reservation;
import io.till.core.ReservationId;
import io.till.core.ReservationState;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What the callers were actually told, and whether the ledger can account for it.
 *
 * <p>{@link Invariants} answers a different question. It asks whether the ledger is consistent with
 * itself, and a ledger can be flawlessly consistent while handing a caller an answer that was never
 * true: told its hold was created when nothing was written, told the same key two different things,
 * told stock left when it did not. Those are the failures a customer notices, and none of them is
 * visible from inside the ledger.
 *
 * <p>So this keeps every answer a caller received and, once the run has drained, checks it against
 * the final state:
 *
 * <ul>
 *   <li><b>One key, one answer.</b> Every observation under an idempotency key is identical,
 *       including the reservation id inside it. This is the property that fails the moment a retry
 *       becomes a second order.
 *   <li><b>A hold that was promised exists</b>, with the lines and the deadline the caller was told.
 *   <li><b>A commit that was promised happened</b>, and the reservation is in the state the caller
 *       was told it was in.
 *   <li><b>Nothing left the building unannounced.</b> Every committed reservation in the ledger was
 *       observed as committed by somebody. A commit nobody was told about is stock that vanished.
 * </ul>
 *
 * <p>What it deliberately does not attempt is a full linearizability search over the history. Stock
 * is a counter with an "out of stock" answer that depends on every other operation in flight, and
 * deciding whether a refusal was justified requires choosing a serialisation point for every
 * concurrent operation at once. The invariants above are checked after every step instead, which is
 * a stronger statement about the interior and a weaker one about the boundary; {@code docs/testing.md}
 * says so rather than leaving it implied.
 */
public final class History {

    /**
     * One answer, as the caller saw it.
     *
     * @param step when it was observed
     * @param client which caller
     * @param command what it asked for
     * @param outcome what it was told
     */
    public record Observation(long step, int client, Command command, Outcome outcome) {}

    private final List<Observation> observations = new ArrayList<>();

    void record(long step, int client, Command command, Outcome outcome) {
        observations.add(new Observation(step, client, command, outcome));
    }

    /**
     * Every answer given during the run, in the order they were given.
     *
     * @return an unmodifiable copy
     */
    public List<Observation> observations() {
        return List.copyOf(observations);
    }

    /**
     * Cross-checks the history against the final state of the ledger.
     *
     * @param seed the run's seed, quoted in failures
     * @param inspector the ledger after the run has drained
     * @throws InvariantViolation on the first discrepancy
     */
    public void check(long seed, LedgerInspector inspector) {
        Map<ReservationId, Reservation> byId = new HashMap<>();
        inspector.allReservations().forEach(reservation -> byId.put(reservation.id(), reservation));

        oneKeyOneAnswer(seed);
        promisedHoldsExist(seed, byId);
        promisedCommitsHappened(seed, byId);
        nothingLeftUnannounced(seed, byId);
    }

    private void oneKeyOneAnswer(long seed) {
        Map<IdempotencyKey, Observation> first = new LinkedHashMap<>();
        for (Observation observation : observations) {
            IdempotencyKey key = observation.command().idempotencyKey().orElse(null);
            if (key == null) {
                continue;
            }
            Observation earlier = first.putIfAbsent(key, observation);
            if (earlier != null && !earlier.outcome().equals(observation.outcome())) {
                throw new InvariantViolation(
                        "One key, one answer",
                        seed,
                        observation.step(),
                        "key " + key + " was answered with " + earlier.outcome() + " at step " + earlier.step()
                                + " and with " + observation.outcome() + " at step " + observation.step());
            }
        }
    }

    private void promisedHoldsExist(long seed, Map<ReservationId, Reservation> byId) {
        for (Observation observation : observations) {
            if (!(observation.outcome() instanceof Outcome.Reserved reserved)) {
                continue;
            }
            Reservation actual = byId.get(reserved.id());
            if (actual == null) {
                throw new InvariantViolation(
                        "A hold that was promised exists",
                        seed,
                        observation.step(),
                        "caller " + observation.client() + " was told it held " + reserved.id()
                                + ", which the ledger has never heard of");
            }
            if (!actual.lines().equals(reserved.lines()) || !actual.expiresAt().equals(reserved.expiresAt())) {
                throw new InvariantViolation(
                        "A hold that was promised exists",
                        seed,
                        observation.step(),
                        reserved.id() + " was promised as " + reserved.lines() + " until " + reserved.expiresAt()
                                + " and is stored as " + actual.lines() + " until " + actual.expiresAt());
            }
        }
    }

    private void promisedCommitsHappened(long seed, Map<ReservationId, Reservation> byId) {
        for (Observation observation : observations) {
            switch (observation.outcome()) {
                case Outcome.Committed committed -> {
                    Reservation actual = require(seed, observation, byId, committed.id());
                    if (actual.state() != ReservationState.COMMITTED) {
                        throw new InvariantViolation(
                                "A commit that was promised happened",
                                seed,
                                observation.step(),
                                committed.id() + " was reported committed and is " + actual.state());
                    }
                }
                case Outcome.Released released -> {
                    Reservation actual = require(seed, observation, byId, released.id());
                    if (actual.state() == ReservationState.COMMITTED || actual.state() == ReservationState.HELD) {
                        throw new InvariantViolation(
                                "A release that was promised happened",
                                seed,
                                observation.step(),
                                released.id() + " was reported released and is " + actual.state());
                    }
                }
                default -> { /* rejections and adjustments are checked by the invariants */ }
            }
        }
    }

    private void nothingLeftUnannounced(long seed, Map<ReservationId, Reservation> byId) {
        Set<ReservationId> announced = new HashSet<>();
        for (Observation observation : observations) {
            if (observation.outcome() instanceof Outcome.Committed committed) {
                announced.add(committed.id());
            }
        }
        for (Reservation reservation : byId.values()) {
            if (reservation.state() == ReservationState.COMMITTED && !announced.contains(reservation.id())) {
                throw new InvariantViolation(
                        "Nothing left the building unannounced",
                        seed,
                        -1,
                        reservation.id() + " is committed for " + reservation.lines()
                                + " and no caller was ever told so");
            }
        }
    }

    private Reservation require(
            long seed, Observation observation, Map<ReservationId, Reservation> byId, ReservationId id) {
        Reservation actual = byId.get(id);
        if (actual == null) {
            throw new InvariantViolation(
                    "A promised reservation exists",
                    seed,
                    observation.step(),
                    "caller " + observation.client() + " was answered about " + id
                            + ", which the ledger has never heard of");
        }
        return actual;
    }

    /**
     * How many answers were given.
     *
     * @return the observation count
     */
    public int size() {
        return observations.size();
    }
}
