package io.till.store.ledger;

import io.till.core.IdempotencyKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/**
 * Turns a customer's idempotency key into one the ledger can be given.
 *
 * <p><b>The ledger's keys are global; a customer's are not.</b> The browser picks a key per checkout
 * attempt, and nothing stops two customers' browsers picking the same string. Passed straight through,
 * the ledger would see one key twice, recognise the second as a retry of the first, and hand the second
 * customer the first customer's reservation. That is not hypothetical: the storefront's first version
 * did exactly that, and it went unnoticed because every demo had a single user.
 *
 * <p>So the ledger never sees the customer's key. It sees a digest of who the customer is, what they
 * are doing, and the key they sent — the same inputs always produce the same ledger key, so a retry is
 * still recognised as a retry, and different customers can no longer collide.
 *
 * <p>A digest rather than concatenation for two more reasons: the ledger's keys are limited to 128
 * characters of a narrow character set, and neither an OIDC subject nor a browser-chosen string is
 * guaranteed to fit either.
 */
public final class LedgerKeys {

    /**
     * Longest client-supplied key accepted. The digest makes length irrelevant to the ledger; the limit
     * is the orders table's, which keeps the key a checkout was placed under, and is the same as the
     * ledger's own so that one rule holds everywhere a key is typed.
     */
    public static final int MAX_CLIENT_KEY = 128;

    private LedgerKeys() {}

    /**
     * @param purpose what the key is for — {@code order}, {@code pay}, {@code cancel}, {@code ops} —
     *     so that one client key reused across two different operations cannot collide either
     * @param principal who is asking: the OIDC subject
     * @param clientKey the key the browser sent
     * @return the key to give the ledger
     * @throws IllegalArgumentException if the client key is blank or too long
     */
    public static IdempotencyKey scoped(String purpose, String principal, String clientKey) {
        return scoped(purpose, principal, "", clientKey);
    }

    /**
     * The same, for an operation on one particular thing.
     *
     * <p>Paying for order A and paying for order B are different requests even if the browser sent the
     * same key for both. Without the order in the material they would reach the ledger as one key used
     * twice for two different commands, and the second would be refused as a reused key — correct by
     * the ledger's rules, and baffling to the customer.
     *
     * @param purpose what the key is for
     * @param principal who is asking
     * @param subject what it is about — the order's id
     * @param clientKey the key the browser sent
     * @return the key to give the ledger
     * @throws IllegalArgumentException if the client key is blank or too long
     */
    public static IdempotencyKey scoped(String purpose, String principal, String subject, String clientKey) {
        if (clientKey == null || clientKey.isBlank()) {
            throw new IllegalArgumentException("an Idempotency-Key is required");
        }
        if (clientKey.length() > MAX_CLIENT_KEY) {
            throw new IllegalArgumentException(
                    "an Idempotency-Key must be at most " + MAX_CLIENT_KEY + " characters, got " + clientKey.length());
        }
        if (clientKey.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("an Idempotency-Key must not contain control characters");
        }
        // Separated by a character none of the parts can contain — control characters are refused
        // above, and neither an OIDC subject nor an order id has one — so ("a", "b:c") and
        // ("a:b", "c") cannot produce the same input to the digest.
        String material = purpose + "\u0000" + principal + "\u0000" + subject + "\u0000" + clientKey;
        return IdempotencyKey.of("store." + purpose + "." + digest(material));
    }

    /**
     * A key for work the store does on its own behalf, not a customer's.
     *
     * @param purpose what the work is
     * @param subject what it is about
     * @return the key
     */
    public static IdempotencyKey system(String purpose, String subject) {
        return IdempotencyKey.of("store." + purpose + "." + digest(purpose + "\u0000" + subject));
    }

    private static String digest(String material) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(material.getBytes(StandardCharsets.UTF_8));
            // URL-safe Base64 without padding: 43 characters, all inside the ledger's character set.
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            // Every JVM is required to provide SHA-256; this cannot happen on a conforming runtime.
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
