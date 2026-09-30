package io.till.store.ledger;

import io.till.client.TillClient;
import io.till.core.IdempotencyKey;
import io.till.core.ReservationState;
import io.till.core.Sku;
import java.util.List;

/** {@link OperatorLedger} over HTTP, with the admin token. */
public final class RemoteOperatorLedger implements OperatorLedger {

    private final TillClient client;

    /**
     * @param client a client holding the ledger's admin token
     */
    public RemoteOperatorLedger(TillClient client) {
        this.client = client;
    }

    @Override
    public TillClient.StockPage stock(int limit, String after) {
        return LedgerErrors.valueOf("listing stock", () -> client.listStock(limit, after));
    }

    @Override
    public List<TillClient.ReservationView> reservations(ReservationState state, int limit) {
        return LedgerErrors.valueOf("listing reservations", () -> client.listReservations(state, limit));
    }

    @Override
    public TillClient.OutboxPage outbox(int limit) {
        return LedgerErrors.valueOf("reading the outbox", () -> client.outbox(limit));
    }

    @Override
    public TillClient.StockView adjust(IdempotencyKey key, Sku sku, long delta) {
        return LedgerErrors.valueOf("adjusting " + sku, () -> client.adjust(key, sku, delta));
    }

    @Override
    public TillClient.StockView shard(IdempotencyKey key, Sku sku, int shards) {
        return LedgerErrors.valueOf("splitting " + sku, () -> client.shard(key, sku, shards));
    }
}
