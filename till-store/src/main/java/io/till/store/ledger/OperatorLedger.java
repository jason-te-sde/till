package io.till.store.ledger;

import io.till.client.TillClient;
import io.till.core.IdempotencyKey;
import io.till.core.ReservationState;
import io.till.core.Sku;
import java.util.List;

/**
 * The ledger, as the operator console needs it — with the admin token.
 *
 * <p>A type of its own rather than a second {@code TillClient} bean, deliberately. Two beans of one
 * type are told apart by a qualifier string, and a qualifier is one typo away from injecting the admin
 * credential into the checkout path. Two types cannot be confused: the order code asks for
 * {@link Holds} and cannot be handed this.
 *
 * <p>Only {@code /api/ops}, which only members of the admin group can reach, uses it. That the store
 * holds the admin token at all is the cost of the ledger never being exposed to a browser: the
 * operator console's requests have to come from somewhere, and a backend holding a service credential
 * behind group-based authorisation is the conventional shape for an internal tool. The alternative —
 * the ledger validating each operator's own token — is written up in the store's design note.
 *
 * <p>An interface for the same reason {@link Holds} is one: the tests serve it from the real kernel in
 * memory, so the console they check shows the reservations the checkout tests actually made.
 */
public interface OperatorLedger {

    /**
     * @param limit at most this many
     * @param after the cursor from the previous page, or null
     * @return a page of stock levels, in SKU order
     */
    TillClient.StockPage stock(int limit, String after);

    /**
     * @param state only reservations in this state, or null for all
     * @param limit at most this many
     * @return reservations, newest first
     */
    List<TillClient.ReservationView> reservations(ReservationState state, int limit);

    /**
     * @param limit at most this many entries
     * @return the unpublished tail of the outbox
     */
    TillClient.OutboxPage outbox(int limit);

    /**
     * @param key namespaced to the operator; see {@link LedgerKeys}
     * @param sku which game
     * @param delta units to add, negative to remove
     * @return the level afterwards
     * @throws LedgerRejection if the ledger refused
     */
    TillClient.StockView adjust(IdempotencyKey key, Sku sku, long delta);

    /**
     * @param key namespaced to whoever asked; see {@link LedgerKeys}
     * @param sku which game
     * @param shards how many rows its stock should be kept in at least
     * @return the level afterwards, and how many rows it is in
     * @throws LedgerRejection if the ledger refused
     */
    TillClient.StockView shard(IdempotencyKey key, Sku sku, int shards);
}
