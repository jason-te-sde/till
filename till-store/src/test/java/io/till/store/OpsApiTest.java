package io.till.store;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/**
 * The operator console's API: what an operator can see, and the one thing they can change.
 *
 * <p>Who may call it is {@link SecurityTest}'s subject; here every request is an operator's.
 */
class OpsApiTest extends StoreTest {

    @Nested
    @DisplayName("stock")
    class Stock {

        @Test
        @DisplayName("comes back in SKU order, with each game's title beside its SKU")
        void titled() throws Exception {
            ledger.stock("tessera", 5);
            ledger.stock("canopy", 2);

            mvc.perform(get("/api/ops/stock").with(operator("olivia")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items[*].sku", contains("canopy", "tessera")))
                    .andExpect(jsonPath("$.items[*].title", contains("Canopy", "Tessera")))
                    .andExpect(jsonPath("$.items[1].onHand").value(5))
                    .andExpect(jsonPath("$.items[1].available").value(5))
                    .andExpect(jsonPath("$.nextAfter", nullValue()));
        }

        @Test
        @DisplayName("pages with a cursor, and says when there is no more")
        void pages() throws Exception {
            ledger.stock("canopy", 1);
            ledger.stock("hexfall", 1);
            ledger.stock("tessera", 1);

            mvc.perform(get("/api/ops/stock").param("limit", "2").with(operator("olivia")))
                    .andExpect(jsonPath("$.items[*].sku", contains("canopy", "hexfall")))
                    .andExpect(jsonPath("$.nextAfter").value("hexfall"));
            mvc.perform(get("/api/ops/stock").param("limit", "2").param("after", "hexfall").with(operator("olivia")))
                    .andExpect(jsonPath("$.items[*].sku", contains("tessera")))
                    .andExpect(jsonPath("$.nextAfter", nullValue()));
        }

        @Test
        @DisplayName("shows a SKU the catalogue does not sell, without inventing a title for it")
        void unknownToTheCatalogue() throws Exception {
            ledger.stock("prototype-x", 1);

            mvc.perform(get("/api/ops/stock").with(operator("olivia")))
                    .andExpect(jsonPath("$.items[0].sku").value("prototype-x"))
                    .andExpect(jsonPath("$.items[0].title", nullValue()));
        }
    }

    @Nested
    @DisplayName("reservations")
    class Reservations {

        @Test
        @DisplayName("show what was recorded and what is true now — a hold past its deadline is expired either way")
        void effectiveState() throws Exception {
            ledger.stock("tessera", 5);
            placeOrder("alice", "tessera", 2);
            clock.advance(Duration.ofMinutes(16));

            mvc.perform(get("/api/ops/reservations").with(operator("olivia")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items", hasSize(1)))
                    .andExpect(jsonPath("$.items[0].state").value("HELD"))
                    .andExpect(jsonPath("$.items[0].effectiveState").value("EXPIRED"))
                    .andExpect(jsonPath("$.items[0].lines[0].sku").value("tessera"))
                    .andExpect(jsonPath("$.items[0].lines[0].quantity").value(2))
                    .andExpect(jsonPath("$.items[0].expiresAt").value("2026-09-18T12:15:00Z"));
        }

        @Test
        @DisplayName("filter by recorded state")
        void filtered() throws Exception {
            ledger.stock("tessera", 5);
            placeOrder("alice", "tessera", 1);

            mvc.perform(get("/api/ops/reservations").param("state", "HELD").with(operator("olivia")))
                    .andExpect(jsonPath("$.items", hasSize(1)));
            mvc.perform(get("/api/ops/reservations").param("state", "COMMITTED").with(operator("olivia")))
                    .andExpect(jsonPath("$.items", hasSize(0)));
        }
    }

    @Nested
    @DisplayName("the outbox")
    class Outbox {

        @Test
        @DisplayName("shows what is waiting to be published, and empties as it is")
        void backlog() throws Exception {
            ledger.stock("tessera", 5);

            mvc.perform(get("/api/ops/outbox").with(operator("olivia")))
                    .andExpect(jsonPath("$.backlog").value(1))
                    .andExpect(jsonPath("$.items[0].payload", containsString("tessera")));

            ledger.deliver(projector);

            mvc.perform(get("/api/ops/outbox").with(operator("olivia")))
                    .andExpect(jsonPath("$.backlog").value(0))
                    .andExpect(jsonPath("$.items", hasSize(0)));
        }
    }

    @Nested
    @DisplayName("adjusting stock")
    class Adjusting {

        @Test
        @DisplayName("changes the ledger and answers with the level it left, titled")
        void adjusts() throws Exception {
            adjust("olivia", "tessera", key(), "{\"delta\":7}")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.sku").value("tessera"))
                    .andExpect(jsonPath("$.title").value("Tessera"))
                    .andExpect(jsonPath("$.onHand").value(7))
                    .andExpect(jsonPath("$.available").value(7));
            adjust("olivia", "tessera", key(), "{\"delta\":-2}").andExpect(jsonPath("$.onHand").value(5));
        }

        @Test
        @DisplayName("sent twice with one key is applied once")
        void idempotent() throws Exception {
            String key = key();
            adjust("olivia", "tessera", key, "{\"delta\":7}").andExpect(jsonPath("$.onHand").value(7));
            adjust("olivia", "tessera", key, "{\"delta\":7}").andExpect(jsonPath("$.onHand").value(7));
        }

        @Test
        @DisplayName("from two operators with the same key is two adjustments, not one operator's retry")
        void keysArePerOperator() throws Exception {
            String key = "restock-monday";
            adjust("olivia", "tessera", key, "{\"delta\":1}").andExpect(jsonPath("$.onHand").value(1));
            adjust("oscar", "tessera", key, "{\"delta\":1}").andExpect(jsonPath("$.onHand").value(2));
        }

        @Test
        @DisplayName("cannot take away units that are not there or already promised")
        void cannotGoBelowReserved() throws Exception {
            ledger.stock("tessera", 3);
            placeOrder("alice", "tessera", 2);

            adjust("olivia", "tessera", key(), "{\"delta\":-2}")
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("INSUFFICIENT_STOCK"));
        }

        @Test
        @DisplayName("refuses a zero, an absurd or a missing delta, and a missing key")
        void refusesNonsense() throws Exception {
            adjust("olivia", "tessera", key(), "{\"delta\":0}")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
            adjust("olivia", "tessera", key(), "{\"delta\":100001}").andExpect(status().isBadRequest());
            adjust("olivia", "tessera", key(), "{}")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_BODY"));
            mvc.perform(post("/api/ops/stock/tessera/adjust")
                            .with(operator("olivia"))
                            .with(csrfToken())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"delta\":1}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("MISSING_HEADER"));
        }

        @Test
        @DisplayName("while the ledger is unreachable is a 503 that says to retry")
        void ledgerDown() throws Exception {
            ledger.unavailable(true);

            adjust("olivia", "tessera", key(), "{\"delta\":1}")
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("LEDGER_UNAVAILABLE"));
        }
    }

    private ResultActions adjust(String operator, String sku, String key, String body) throws Exception {
        return mvc.perform(post("/api/ops/stock/" + sku + "/adjust")
                .with(operator(operator))
                .with(csrfToken())
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private void placeOrder(String customer, String sku, int quantity) throws Exception {
        mvc.perform(post("/api/orders")
                        .with(customer(customer))
                        .with(csrfToken())
                        .header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lines\":[{\"sku\":\"" + sku + "\",\"quantity\":" + quantity + "}]}"))
                .andExpect(status().isCreated());
    }
}
