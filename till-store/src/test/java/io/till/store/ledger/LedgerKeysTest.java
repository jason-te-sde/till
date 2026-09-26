package io.till.store.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.till.core.IdempotencyKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class LedgerKeysTest {

    @Test
    @DisplayName("the same request always makes the same ledger key, so a retry is still a retry")
    void deterministic() {
        assertEquals(LedgerKeys.scoped("order", "alice", "k-1"), LedgerKeys.scoped("order", "alice", "k-1"));
        assertEquals(LedgerKeys.scoped("pay", "alice", "o-1", "k-1"), LedgerKeys.scoped("pay", "alice", "o-1", "k-1"));
    }

    @Test
    @DisplayName("two customers sending the same key make two ledger keys")
    void perCustomer() {
        assertNotEquals(LedgerKeys.scoped("order", "alice", "k-1"), LedgerKeys.scoped("order", "bob", "k-1"));
    }

    @Test
    @DisplayName("one key reused for a different operation, or a different order, makes a different ledger key")
    void perPurposeAndSubject() {
        assertNotEquals(LedgerKeys.scoped("order", "alice", "k-1"), LedgerKeys.scoped("cancel", "alice", "k-1"));
        assertNotEquals(LedgerKeys.scoped("pay", "alice", "o-1", "k-1"), LedgerKeys.scoped("pay", "alice", "o-2", "k-1"));
    }

    @Test
    @DisplayName("moving a character from one part to the next does not produce the same input")
    void partsCannotRunTogether() {
        // Concatenated with a plain separator, ("a", "b:c") and ("a:b", "c") would be one string.
        assertNotEquals(LedgerKeys.scoped("order", "a", "b:c"), LedgerKeys.scoped("order", "a:b", "c"));
        assertNotEquals(LedgerKeys.scoped("pay", "alice", "o-1", "k"), LedgerKeys.scoped("pay", "alice", "o-", "1k"));
    }

    @Test
    @DisplayName("any key a browser may send becomes one the ledger accepts")
    void fitsTheLedger() {
        // As long as a key may be, and made of what the ledger's own keys may not contain.
        String awkward = "ключ 🔑 / ? # & = % ".repeat(10).substring(0, LedgerKeys.MAX_CLIENT_KEY);
        IdempotencyKey key = LedgerKeys.scoped("order", "a subject | with spaces", awkward);

        assertTrue(key.value().length() <= 128, key.value());
        assertTrue(key.value().matches("[A-Za-z0-9._:@=+/-]+"), key.value());
        assertTrue(key.value().startsWith("store.order."), key.value());
    }

    @ParameterizedTest(name = "refuses {0}")
    @ValueSource(strings = {"", "   ", "tab\tinside", "line\nbreak"})
    @DisplayName("refuses a blank key, or one with control characters in it")
    void refusesBadKeys(String key) {
        assertThrows(IllegalArgumentException.class, () -> LedgerKeys.scoped("order", "alice", key));
    }

    @Test
    @DisplayName("refuses a key longer than the limit, and says what the limit is")
    void refusesLongKeys() {
        LedgerKeys.scoped("order", "alice", "k".repeat(LedgerKeys.MAX_CLIENT_KEY));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> LedgerKeys.scoped("order", "alice", "k".repeat(LedgerKeys.MAX_CLIENT_KEY + 1)));
        assertTrue(e.getMessage().contains(String.valueOf(LedgerKeys.MAX_CLIENT_KEY)), e.getMessage());
    }

    @Test
    @DisplayName("the store's own work gets stable keys of its own, apart from any customer's")
    void systemKeys() {
        assertEquals(LedgerKeys.system("demo-stock", "tessera"), LedgerKeys.system("demo-stock", "tessera"));
        assertNotEquals(LedgerKeys.system("demo-stock", "tessera"), LedgerKeys.system("demo-stock", "canopy"));
        assertNotEquals(LedgerKeys.system("demo-stock", "tessera"), LedgerKeys.scoped("demo-stock", "", "tessera"));
    }
}
