package io.till.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * Something a caller wants to happen.
 *
 * <p>Every command carries the values the kernel needs and nothing it could look up, which is what
 * makes {@link Kernel#decide} a function of its arguments alone. In particular a
 * {@link Reserve} carries the identifier of the reservation it will create: inventing one inside the
 * kernel would mean reaching for a random source, and a run would stop being reproducible.
 */
public sealed interface Command {

    /** Longest hold a caller may ask for. Beyond this, a hold is a stock level, not a hold. */
    Duration MAX_TTL = Duration.ofDays(30);

    /**
     * The caller's key for this attempt, if it has one.
     *
     * @return empty only for {@link Sweep}, which no caller issues
     */
    Optional<IdempotencyKey> idempotencyKey();

    /**
     * A stable digest of everything that makes this request the request it is.
     *
     * <p>Compared against the fingerprint stored with a key to tell a retry from a reuse. Two
     * properties matter and neither is obvious:
     *
     * <ul>
     *   <li>A {@link Reserve} fingerprint <b>excludes the reservation id</b>. A server that mints an
     *       id per HTTP request would otherwise produce a different fingerprint for every retry of
     *       the same call, and every retry would be rejected as a key reuse.
     *   <li>Lines are canonicalised first, so a body that lists the same SKUs in a different order
     *       is the same request.
     * </ul>
     *
     * @return 64 hex characters of SHA-256
     */
    String fingerprint();

    /**
     * The reservation this command is about, if it names one.
     *
     * <p>Every {@link Ledger} needs this to know what to load, and a switch over the command kinds
     * in each adapter is a switch that goes stale the day a kind is added.
     *
     * @return the reservation id, or empty for {@link Adjust} and {@link Sweep}
     */
    Optional<ReservationId> targetReservation();

    /**
     * The SKUs the command names by itself.
     *
     * <p>Not the whole scope: a commit's SKUs come from the reservation it names, and a sweep's come
     * from whatever it finds. A ledger starts here and adds what it reads.
     *
     * @return the SKUs in the command, possibly empty
     */
    List<Sku> declaredSkus();

    /**
     * Take a hold on stock.
     *
     * @param key the caller's key for this attempt
     * @param reservationId the identifier the new hold will have, the caller's to keep unique
     * @param lines what to hold; at least one, no SKU twice, canonicalised on construction
     * @param ttl how long the hold lasts, positive and at most {@link #MAX_TTL}
     */
    record Reserve(IdempotencyKey key, ReservationId reservationId, List<Line> lines, Duration ttl)
            implements Command {

        public Reserve {
            requireKey(key);
            if (reservationId == null) {
                throw new IllegalArgumentException("reservation id must not be null");
            }
            if (ttl == null || ttl.isZero() || ttl.isNegative()) {
                throw new IllegalArgumentException("ttl must be positive, got " + ttl);
            }
            if (ttl.compareTo(MAX_TTL) > 0) {
                throw new IllegalArgumentException("ttl must be at most " + MAX_TTL + ", got " + ttl);
            }
            lines = Reservation.canonical(lines);
        }

        @Override
        public Optional<IdempotencyKey> idempotencyKey() {
            return Optional.of(key);
        }

        @Override
        public Optional<ReservationId> targetReservation() {
            return Optional.of(reservationId);
        }

        @Override
        public List<Sku> declaredSkus() {
            return lines.stream().map(Line::sku).toList();
        }

        @Override
        public String fingerprint() {
            StringBuilder sb = new StringBuilder("reserve|").append(ttl.toMillis());
            for (Line line : lines) {
                sb.append('|').append(line.sku()).append(':').append(line.quantity());
            }
            return Command.digest(sb.toString());
        }
    }

    /**
     * Turn a hold into a sale.
     *
     * @param key the caller's key for this attempt
     * @param reservationId which hold
     */
    record Commit(IdempotencyKey key, ReservationId reservationId) implements Command {

        public Commit {
            requireKey(key);
            if (reservationId == null) {
                throw new IllegalArgumentException("reservation id must not be null");
            }
        }

        @Override
        public Optional<IdempotencyKey> idempotencyKey() {
            return Optional.of(key);
        }

        @Override
        public Optional<ReservationId> targetReservation() {
            return Optional.of(reservationId);
        }

        @Override
        public List<Sku> declaredSkus() {
            return List.of();
        }

        @Override
        public String fingerprint() {
            return Command.digest("commit|" + reservationId);
        }
    }

    /**
     * Give a hold back.
     *
     * @param key the caller's key for this attempt
     * @param reservationId which hold
     */
    record Release(IdempotencyKey key, ReservationId reservationId) implements Command {

        public Release {
            requireKey(key);
            if (reservationId == null) {
                throw new IllegalArgumentException("reservation id must not be null");
            }
        }

        @Override
        public Optional<IdempotencyKey> idempotencyKey() {
            return Optional.of(key);
        }

        @Override
        public Optional<ReservationId> targetReservation() {
            return Optional.of(reservationId);
        }

        @Override
        public List<Sku> declaredSkus() {
            return List.of();
        }

        @Override
        public String fingerprint() {
            return Command.digest("release|" + reservationId);
        }
    }

    /**
     * Change on-hand stock directly: a delivery arriving, a breakage written off, a SKU created.
     *
     * <p>A positive delta on a SKU that has no row creates it. A negative delta that would take
     * on-hand below what is already reserved is refused, because those units are promised.
     *
     * @param key the caller's key for this attempt
     * @param sku which SKU
     * @param delta units to add, negative to remove, never zero
     */
    record Adjust(IdempotencyKey key, Sku sku, long delta) implements Command {

        public Adjust {
            requireKey(key);
            if (sku == null) {
                throw new IllegalArgumentException("sku must not be null");
            }
            if (delta == 0) {
                throw new IllegalArgumentException("adjustment must not be zero for " + sku);
            }
            if (Math.abs(delta) > Line.MAX_QUANTITY) {
                throw new IllegalArgumentException(
                        "adjustment must be at most " + Line.MAX_QUANTITY + " in magnitude, got " + delta);
            }
        }

        @Override
        public Optional<IdempotencyKey> idempotencyKey() {
            return Optional.of(key);
        }

        @Override
        public Optional<ReservationId> targetReservation() {
            return Optional.empty();
        }

        @Override
        public List<Sku> declaredSkus() {
            return List.of(sku);
        }

        @Override
        public String fingerprint() {
            return Command.digest("adjust|" + sku + "|" + delta);
        }
    }

    /**
     * Write off holds that have run out of time.
     *
     * <p>The only command with no key, because no caller issues it: it is background work, it
     * changes nothing a caller was promised, and it is safe to run at any moment or never. Running
     * it returns expired stock to {@code available} sooner than the next command touching those
     * SKUs would.
     *
     * @param limit how many reservations to write off at most, at least 1
     */
    record Sweep(int limit) implements Command {

        public Sweep {
            if (limit < 1) {
                throw new IllegalArgumentException("sweep limit must be at least 1, got " + limit);
            }
        }

        @Override
        public Optional<IdempotencyKey> idempotencyKey() {
            return Optional.empty();
        }

        @Override
        public Optional<ReservationId> targetReservation() {
            return Optional.empty();
        }

        @Override
        public List<Sku> declaredSkus() {
            return List.of();
        }

        @Override
        public String fingerprint() {
            return Command.digest("sweep");
        }
    }

    private static void requireKey(IdempotencyKey key) {
        if (key == null) {
            throw new IllegalArgumentException("idempotency key must not be null");
        }
    }

    /**
     * SHA-256 of a string, as lower-case hex.
     *
     * @param canonical the canonical form of the request
     * @return 64 hex characters
     */
    private static String digest(String canonical) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(sha256.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // Every conforming JRE ships SHA-256. If this one does not, nothing downstream is
            // going to work either, and pretending otherwise would hide the reason.
            throw new IllegalStateException("SHA-256 is required and this JRE does not have it", e);
        }
    }
}
