package io.till.store.catalogue;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * What the store believes is still buyable — the read side of the projection.
 *
 * <p><b>A cache with a timestamp on it, not an authority.</b> It lags the ledger by however long an
 * event took to arrive, and a page that showed "3 left" a second after the third sold has not
 * malfunctioned: the ledger decides at the moment of the reservation, which is why a stale number
 * here costs a customer one refused checkout and never an oversell.
 *
 * <p>Written by {@code Projector}, read here, and deliberately never cached anywhere else. The
 * catalogue beside it can be cached for minutes; this is the one number on a store page that should
 * be as fresh as it can be.
 */
@Repository
public class Availability {

    private final JdbcClient jdbc;

    Availability(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @param sku a SKU
     * @return what is believed, or empty if nothing has been heard about it
     */
    public Optional<Level> of(String sku) {
        return jdbc.sql("select sku, on_hand, reserved, available, updated_at from store_availability where sku = ?")
                .param(sku)
                .query((rs, row) -> read(rs.getString("sku"), rs.getLong("on_hand"), rs.getLong("reserved"),
                        rs.getLong("available"), rs.getObject("updated_at", OffsetDateTime.class)))
                .optional();
    }

    /**
     * One query for a whole page rather than one per game. The n+1 version is invisible with eight
     * games and the reason a listing times out with eight hundred.
     *
     * @param skus the SKUs on the page
     * @return what is known, keyed by SKU; an unknown SKU is simply absent
     */
    public Map<String, Level> of(Collection<String> skus) {
        if (skus.isEmpty()) {
            return Map.of();
        }
        Map<String, Level> levels = new LinkedHashMap<>();
        jdbc.sql("select sku, on_hand, reserved, available, updated_at from store_availability where sku in (:skus)")
                .param("skus", skus)
                .query((rs, row) -> read(rs.getString("sku"), rs.getLong("on_hand"), rs.getLong("reserved"),
                        rs.getLong("available"), rs.getObject("updated_at", OffsetDateTime.class)))
                .list()
                .forEach(level -> levels.put(level.sku(), level));
        return levels;
    }

    private static Level read(String sku, long onHand, long reserved, long available, OffsetDateTime at) {
        return new Level(sku, onHand, reserved, available, at.toInstant());
    }

    /**
     * What is believed about one SKU, and when it was last heard.
     *
     * @param sku the SKU
     * @param onHand units the ledger said it had
     * @param reserved units held
     * @param available what a new hold could take, as of {@code updatedAt}
     * @param updatedAt the decision instant of the last event applied — the ledger's clock, not this
     *     service's, so the age of the number is the age of the news
     */
    public record Level(String sku, long onHand, long reserved, long available, Instant updatedAt) {

        /**
         * What to show a customer: never below zero, even while the projection is catching up with a
         * SKU whose adjustment arrived after its holds.
         *
         * @return units a customer can try to buy
         */
        public long buyable() {
            return Math.max(0, available);
        }
    }
}
