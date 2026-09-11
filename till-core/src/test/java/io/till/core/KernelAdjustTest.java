package io.till.core;

import static io.till.core.Fixtures.T0;
import static io.till.core.Fixtures.key;
import static io.till.core.Fixtures.sku;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class KernelAdjustTest {

    @Test
    @DisplayName("a positive adjustment to a SKU with no row creates it")
    void createsTheRow() {
        Snapshot snapshot = Fixtures.snapshot().absent(sku("widget")).build();

        Decision decision = Kernel.decide(snapshot, new Command.Adjust(key("d1"), sku("widget"), 100), T0);

        Outcome.Adjusted adjusted = assertInstanceOf(Outcome.Adjusted.class, decision.outcome());
        assertEquals(100, adjusted.onHand());
        assertEquals(100, adjusted.available());

        Mutation.PutStock put = Fixtures.only(decision, Mutation.PutStock.class);
        assertTrue(put.isInsert(), "the row did not exist, so this must fail if it turns out to");
        assertEquals(StockItem.ABSENT, put.expectedVersion());
    }

    @Test
    @DisplayName("a negative adjustment to a SKU with no row is refused")
    void refusesToRemoveFromNothing() {
        Snapshot snapshot = Fixtures.snapshot().absent(sku("ghost")).build();

        Decision decision = Kernel.decide(snapshot, new Command.Adjust(key("d1"), sku("ghost"), -1), T0);

        assertEquals(RejectionCode.UNKNOWN_SKU, Fixtures.rejection(decision).code());
    }

    @Test
    @DisplayName("stock cannot be written off below what is already reserved")
    void refusesToRemovePromisedStock() {
        Snapshot snapshot = Fixtures.snapshot().stock(sku("widget"), 10, 7, 3).build();

        Decision decision = Kernel.decide(snapshot, new Command.Adjust(key("d1"), sku("widget"), -5), T0);

        Outcome.Rejected rejected = Fixtures.rejection(decision);
        assertEquals(RejectionCode.INSUFFICIENT_STOCK, rejected.code());
        assertEquals(
                List.of(new Outcome.Shortfall(sku("widget"), 5, 3)),
                rejected.shortfalls(),
                "three could have been written off; seven are promised to somebody");
    }

    @Test
    @DisplayName("stock can be written off down to exactly what is reserved")
    void allowsRemovingEverythingUnreserved() {
        Snapshot snapshot = Fixtures.snapshot().stock(sku("widget"), 10, 7, 3).build();

        Decision decision = Kernel.decide(snapshot, new Command.Adjust(key("d1"), sku("widget"), -3), T0);

        Outcome.Adjusted adjusted = assertInstanceOf(Outcome.Adjusted.class, decision.outcome());
        assertEquals(7, adjusted.onHand());
        assertEquals(0, adjusted.available());
    }

    @Test
    @DisplayName("an adjustment event is named by its key, which is what makes it unique")
    void eventDedupeKey() {
        Snapshot snapshot = Fixtures.snapshot().stock(sku("widget"), 10, 0, 0).build();

        Decision decision = Kernel.decide(snapshot, new Command.Adjust(key("delivery-41"), sku("widget"), 5), T0);

        Event.StockAdjusted event = Fixtures.onlyEvent(decision, Event.StockAdjusted.class);
        assertEquals("adjusted:delivery-41", event.dedupeKey());
        assertEquals(15, event.onHand());
        assertEquals(5, event.delta());
    }

    @Test
    @DisplayName("an adjustment of zero is a client bug, not a no-op")
    void zeroIsRejectedAtConstruction() {
        assertThrows(
                IllegalArgumentException.class, () -> new Command.Adjust(key("d1"), sku("widget"), 0));
    }
}
