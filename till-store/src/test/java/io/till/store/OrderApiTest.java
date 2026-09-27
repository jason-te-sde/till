package io.till.store;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.till.core.IdempotencyKey;
import io.till.core.ReservationId;
import io.till.core.ReservationState;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Checkout, against the real reservation kernel.
 *
 * <p>Every refusal and every expiry here is decided by the same kernel the ledger service runs, so a
 * test that says "the second customer is told how short they fell" is a statement about the actual
 * rules, not about a stub's opinion of them.
 */
class OrderApiTest extends StoreTest {

    private static final String ALICE = "alice";
    private static final String BOB = "bob";

    @Nested
    @DisplayName("placing an order")
    class Placing {

        @Test
        @DisplayName("holds the stock and prices the order on the server")
        void placesAndHolds() throws Exception {
            ledger.stock("sunless-orbit", 5);

            place(ALICE, key(), "{\"lines\":[{\"sku\":\"sunless-orbit\",\"quantity\":2}]}")
                    .andExpect(status().isCreated())
                    .andExpect(header().string("Location", containsString("/api/orders/")))
                    .andExpect(jsonPath("$.status").value("PENDING"))
                    // 2 × $44.99, the sale price — read from the catalogue, never from the request.
                    .andExpect(jsonPath("$.totalCents").value(8998))
                    .andExpect(jsonPath("$.currency").value("USD"))
                    .andExpect(jsonPath("$.lines[0].title").value("Sunless Orbit"))
                    .andExpect(jsonPath("$.lines[0].unitPriceCents").value(4499))
                    .andExpect(jsonPath("$.lines[0].cover").value("orbit"))
                    .andExpect(jsonPath("$.expiresAt").value("2026-09-18T12:15:00Z"))
                    .andExpect(jsonPath("$.closedAt", nullValue()));

            // Held, not sold: on-hand untouched, two of five spoken for.
            assertEquals(5, ledger.level("sunless-orbit").orElseThrow().onHand());
            assertEquals(2, ledger.level("sunless-orbit").orElseThrow().reserved());
        }

        @Test
        @DisplayName("a retried request returns the order it already placed, and holds nothing more")
        void retryIsIdempotent() throws Exception {
            ledger.stock("tessera", 5);
            String key = key();
            String body = "{\"lines\":[{\"sku\":\"tessera\",\"quantity\":1}]}";

            String first = orderId(place(ALICE, key, body).andExpect(status().isCreated()));
            String second = orderId(place(ALICE, key, body).andExpect(status().isCreated()));

            // A double click, or a response lost on the way back. One order, one unit held.
            assertEquals(first, second);
            assertEquals(1, ledger.level("tessera").orElseThrow().reserved());
        }

        @Test
        @DisplayName("the same key from two customers is two orders, not one customer's order handed to the other")
        void keysAreNamespacedPerCustomer() throws Exception {
            ledger.stock("tessera", 5);
            String sharedKey = "cart-1";
            String body = "{\"lines\":[{\"sku\":\"tessera\",\"quantity\":1}]}";

            String alices = orderId(place(ALICE, sharedKey, body));
            String bobs = orderId(place(BOB, sharedKey, body));

            // The regression this exists for: the ledger's keys are global, and a browser-chosen key
            // passed straight through would have made the ledger replay Alice's hold for Bob.
            assertNotEquals(alices, bobs);
            assertEquals(2, ledger.level("tessera").orElseThrow().reserved());
        }

        @Test
        @DisplayName("the same key for a different basket is refused, not silently answered with the first")
        void keyReuseIsRefused() throws Exception {
            ledger.stock("tessera", 5);
            ledger.stock("hexfall", 5);
            String key = key();

            place(ALICE, key, "{\"lines\":[{\"sku\":\"tessera\",\"quantity\":1}]}").andExpect(status().isCreated());
            place(ALICE, key, "{\"lines\":[{\"sku\":\"hexfall\",\"quantity\":1}]}")
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        }

        @Test
        @DisplayName("more than there is, is refused with how far short each game fell")
        void outOfStock() throws Exception {
            ledger.stock("ninefold", 1);
            ledger.stock("tessera", 10);

            place(ALICE, key(), "{\"lines\":[{\"sku\":\"ninefold\",\"quantity\":3},{\"sku\":\"tessera\",\"quantity\":2}]}")
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("INSUFFICIENT_STOCK"))
                    .andExpect(jsonPath("$.shortfalls", hasSize(1)))
                    .andExpect(jsonPath("$.shortfalls[0].sku").value("ninefold"))
                    .andExpect(jsonPath("$.shortfalls[0].requested").value(3))
                    .andExpect(jsonPath("$.shortfalls[0].available").value(1));

            // All or nothing: the tessera that was available was not held on its own.
            assertEquals(0, ledger.level("tessera").orElseThrow().reserved());
        }

        @Test
        @DisplayName("a game the ledger has never stocked reads as unavailable, not as missing")
        void neverStocked() throws Exception {
            place(ALICE, key(), "{\"lines\":[{\"sku\":\"glasswing\",\"quantity\":1}]}")
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("UNKNOWN_SKU"));
        }

        @Test
        @DisplayName("a game the store does not sell, a duplicate line and eleven copies are all refused")
        void badBaskets() throws Exception {
            ledger.stock("tessera", 50);
            place(ALICE, key(), "{\"lines\":[{\"sku\":\"not-a-game\",\"quantity\":1}]}")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.detail", containsString("not-a-game")));
            place(ALICE, key(), "{\"lines\":[{\"sku\":\"tessera\",\"quantity\":1},{\"sku\":\"tessera\",\"quantity\":1}]}")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.detail", containsString("twice")));
            place(ALICE, key(), "{\"lines\":[{\"sku\":\"tessera\",\"quantity\":11}]}")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_BODY"));
            place(ALICE, key(), "{\"lines\":[]}").andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("without an Idempotency-Key is refused, with the reason")
        void keyIsRequired() throws Exception {
            mvc.perform(post("/api/orders").with(customer(ALICE)).with(csrfToken())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"lines\":[{\"sku\":\"tessera\",\"quantity\":1}]}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("MISSING_HEADER"));
        }

        @Test
        @DisplayName("with a key longer than the store keeps is refused up front, not failed on the way to the database")
        void keyTooLong() throws Exception {
            ledger.stock("tessera", 5);

            place(ALICE, "k".repeat(129), "{\"lines\":[{\"sku\":\"tessera\",\"quantity\":1}]}")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
                    .andExpect(jsonPath("$.detail", containsString("128")));
            assertEquals(0, ledger.level("tessera").orElseThrow().reserved(), "and nothing was held");
        }

        @Test
        @DisplayName("while the ledger is unreachable is a 503 that says the request is safe to retry")
        void ledgerDown() throws Exception {
            ledger.unavailable(true);

            place(ALICE, key(), "{\"lines\":[{\"sku\":\"tessera\",\"quantity\":1}]}")
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(header().string("Retry-After", "2"))
                    .andExpect(jsonPath("$.code").value("LEDGER_UNAVAILABLE"));
        }
    }

    @Nested
    @DisplayName("paying")
    class Paying {

        @Test
        @DisplayName("turns the hold into a sale")
        void pays() throws Exception {
            ledger.stock("canopy", 5);
            String id = orderId(place(ALICE, key(), "{\"lines\":[{\"sku\":\"canopy\",\"quantity\":2}]}"));

            pay(ALICE, id, key())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("PAID"))
                    .andExpect(jsonPath("$.closedAt").value("2026-09-18T12:00:00Z"));

            // Sold: on-hand and reserved fall together.
            assertEquals(3, ledger.level("canopy").orElseThrow().onHand());
            assertEquals(0, ledger.level("canopy").orElseThrow().reserved());
        }

        @Test
        @DisplayName("twice is paid once")
        void payingTwice() throws Exception {
            ledger.stock("canopy", 5);
            String id = orderId(place(ALICE, key(), "{\"lines\":[{\"sku\":\"canopy\",\"quantity\":1}]}"));

            pay(ALICE, id, key()).andExpect(jsonPath("$.status").value("PAID"));
            // A different key: a second click after a reload, not a retry of the first request.
            pay(ALICE, id, key()).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PAID"));

            assertEquals(4, ledger.level("canopy").orElseThrow().onHand(), "one unit left, not two");
        }

        @Test
        @DisplayName("after the hold ran out is gone, and the order says expired")
        void tooLate() throws Exception {
            ledger.stock("canopy", 5);
            String id = orderId(place(ALICE, key(), "{\"lines\":[{\"sku\":\"canopy\",\"quantity\":1}]}"));
            clock.advance(Duration.ofMinutes(16));

            pay(ALICE, id, key())
                    .andExpect(status().isGone())
                    .andExpect(jsonPath("$.code").value("ORDER_EXPIRED"));
            order(ALICE, id).andExpect(jsonPath("$.status").value("EXPIRED"));
        }

        @Test
        @DisplayName("a paid order's response lost on the way back still ends up paid")
        void lostResponse() throws Exception {
            ledger.stock("canopy", 5);
            String id = orderId(place(ALICE, key(), "{\"lines\":[{\"sku\":\"canopy\",\"quantity\":1}]}"));
            ReservationId reservation = reservationOf(id);
            // The commit reaches the ledger but its answer never reaches the store.
            ledger.commit(IdempotencyKey.of("elsewhere"), reservation);
            order(ALICE, id).andExpect(jsonPath("$.status").value("PENDING"));

            // And then the event arrives, as it always does in the end.
            ledger.deliver(projector);

            order(ALICE, id).andExpect(jsonPath("$.status").value("PAID"));
        }
    }

    @Nested
    @DisplayName("cancelling and expiring")
    class Cancelling {

        @Test
        @DisplayName("cancelling gives the stock straight back")
        void cancels() throws Exception {
            ledger.stock("frostline", 3);
            String id = orderId(place(ALICE, key(), "{\"lines\":[{\"sku\":\"frostline\",\"quantity\":3}]}"));
            assertEquals(0, ledger.level("frostline").orElseThrow().available());

            cancel(ALICE, id, key()).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));

            assertEquals(3, ledger.level("frostline").orElseThrow().available());
        }

        @Test
        @DisplayName("a paid order cannot be cancelled")
        void cannotCancelPaid() throws Exception {
            ledger.stock("frostline", 3);
            String id = orderId(place(ALICE, key(), "{\"lines\":[{\"sku\":\"frostline\",\"quantity\":1}]}"));
            pay(ALICE, id, key());

            cancel(ALICE, id, key())
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("ORDER_PAID"));
        }

        @Test
        @DisplayName("an unpaid order past its deadline reads as expired before any event says so")
        void deadlineIsTheTruth() throws Exception {
            ledger.stock("frostline", 3);
            String id = orderId(place(ALICE, key(), "{\"lines\":[{\"sku\":\"frostline\",\"quantity\":1}]}"));

            clock.advance(Duration.ofMinutes(15));

            // Nothing has been written — no sweep, no event — and still the order says expired, because
            // a page showing "awaiting payment" beside a countdown at zero would be arguing with its own
            // clock. The same rule the ledger runs by.
            order(ALICE, id).andExpect(jsonPath("$.status").value("EXPIRED"));
        }

        @Test
        @DisplayName("when the ledger writes the hold off, the order follows")
        void expiryEvent() throws Exception {
            ledger.stock("frostline", 3);
            String id = orderId(place(ALICE, key(), "{\"lines\":[{\"sku\":\"frostline\",\"quantity\":1}]}"));
            clock.advance(Duration.ofMinutes(16));
            // Another customer's order needs the stock, and the ledger reclaims the expired hold on the way.
            place(BOB, key(), "{\"lines\":[{\"sku\":\"frostline\",\"quantity\":3}]}").andExpect(status().isCreated());
            assertEquals(ReservationState.EXPIRED, ledger.reservation(reservationOf(id)).orElseThrow().state());

            ledger.deliver(projector);

            order(ALICE, id).andExpect(jsonPath("$.status").value("EXPIRED")).andExpect(jsonPath("$.closedAt").value("2026-09-18T12:16:00Z"));
        }
    }

    @Nested
    @DisplayName("order history")
    class History {

        @Test
        @DisplayName("lists your orders newest first")
        void mine() throws Exception {
            ledger.stock("tessera", 10);
            String first = orderId(place(ALICE, key(), "{\"lines\":[{\"sku\":\"tessera\",\"quantity\":1}]}"));
            clock.advance(Duration.ofMinutes(1));
            String second = orderId(place(ALICE, key(), "{\"lines\":[{\"sku\":\"tessera\",\"quantity\":2}]}"));

            mvc.perform(get("/api/orders").with(customer(ALICE)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items[*].id", contains(second, first)));
        }

        @Test
        @DisplayName("never shows, pays or cancels somebody else's order — it does not even admit it exists")
        void othersOrdersAreInvisible() throws Exception {
            ledger.stock("tessera", 10);
            String alices = orderId(place(ALICE, key(), "{\"lines\":[{\"sku\":\"tessera\",\"quantity\":1}]}"));

            order(BOB, alices).andExpect(status().isNotFound());
            pay(BOB, alices, key()).andExpect(status().isNotFound());
            cancel(BOB, alices, key()).andExpect(status().isNotFound());
            mvc.perform(get("/api/orders").with(customer(BOB))).andExpect(jsonPath("$.items", hasSize(0)));

            // And it is still Alice's, unpaid and holding its unit.
            order(ALICE, alices).andExpect(jsonPath("$.status").value("PENDING"));
        }
    }

    // --- helpers -----------------------------------------------------------------------------------

    private ResultActions place(String customer, String key, String body) throws Exception {
        return mvc.perform(post("/api/orders")
                .with(customer(customer))
                .with(csrfToken())
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private ResultActions pay(String customer, String id, String key) throws Exception {
        return mvc.perform(post("/api/orders/" + id + "/pay").with(customer(customer)).with(csrfToken()).header("Idempotency-Key", key));
    }

    private ResultActions cancel(String customer, String id, String key) throws Exception {
        return mvc.perform(post("/api/orders/" + id + "/cancel").with(customer(customer)).with(csrfToken()).header("Idempotency-Key", key));
    }

    private ResultActions order(String customer, String id) throws Exception {
        return mvc.perform(get("/api/orders/" + id).with(customer(customer)));
    }

    private static String orderId(ResultActions result) throws Exception {
        return JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.id");
    }

    private ReservationId reservationOf(String orderId) {
        return ReservationId.of(jdbc.sql("select reservation_id from store_order where id = ?::uuid")
                .param(orderId)
                .query(String.class)
                .single());
    }
}
