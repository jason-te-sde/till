package io.till.store.ledger;

import io.till.client.TillClient;
import io.till.core.IdempotencyKey;
import io.till.core.Line;
import io.till.core.Outcome;
import io.till.core.ReservationId;
import java.time.Duration;
import java.util.List;

/**
 * {@link Holds} over HTTP, with the client token.
 *
 * <p>The client already retries a 503 and a dropped connection with the same idempotency key, so by
 * the time a failure reaches here it has been retried; this class only decides what kind of failure it
 * was. See {@link LedgerErrors}.
 */
public final class RemoteHolds implements Holds {

    private final TillClient client;

    /**
     * @param client a client holding the ledger's <b>client</b> token — never the admin one
     */
    public RemoteHolds(TillClient client) {
        this.client = client;
    }

    @Override
    public Outcome reserve(IdempotencyKey key, List<Line> lines, Duration holdFor) {
        return LedgerErrors.outcomeOf("reserving " + lines.size() + " lines", () -> client.reserve(key, lines, holdFor));
    }

    @Override
    public Outcome commit(IdempotencyKey key, ReservationId id) {
        return LedgerErrors.outcomeOf("committing " + id, () -> client.commit(key, id));
    }

    @Override
    public Outcome release(IdempotencyKey key, ReservationId id) {
        return LedgerErrors.outcomeOf("releasing " + id, () -> client.release(key, id));
    }
}
