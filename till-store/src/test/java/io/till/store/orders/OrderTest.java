package io.till.store.orders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.till.core.ReservationId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OrderTest {

    private static final Instant PLACED = Instant.parse("2026-09-18T12:00:00Z");
    private static final Instant DEADLINE = Instant.parse("2026-09-18T12:15:00Z");

    @Test
    @DisplayName("an unpaid order is pending right up to its deadline, and expired from that instant")
    void deadlineIsTheTruth() {
        Order order = order(Order.Status.PENDING);
        assertEquals(Order.Status.PENDING, order.effectiveStatus(DEADLINE.minusMillis(1)));
        assertEquals(Order.Status.EXPIRED, order.effectiveStatus(DEADLINE));
        assertEquals(Order.Status.EXPIRED, order.effectiveStatus(DEADLINE.plusSeconds(3600)));
    }

    @Test
    @DisplayName("a closed order stays what it closed as, however late it is read")
    void closedStaysClosed() {
        for (Order.Status closed : List.of(Order.Status.PAID, Order.Status.CANCELLED, Order.Status.EXPIRED)) {
            assertEquals(closed, order(closed).effectiveStatus(DEADLINE.plusSeconds(86_400)));
        }
    }

    @Test
    @DisplayName("keeps its own copy of its lines")
    void linesAreCopied() {
        List<Order.Line> lines = new ArrayList<>(List.of(new Order.Line("tessera", "Tessera", 1999, 1)));
        Order order = new Order(UUID.randomUUID(), "alice", "k", ReservationId.of("r"), Order.Status.PENDING, 1999, "USD",
                PLACED, DEADLINE, null, lines);
        lines.clear();
        assertEquals(1, order.lines().size());
    }

    @Test
    @DisplayName("a subtotal that would overflow is an error, not a small or negative number")
    void subtotalOverflow() {
        assertEquals(3 * 1999, new Order.Line("tessera", "Tessera", 1999, 3).subtotalCents());
        assertThrows(ArithmeticException.class, () -> new Order.Line("x", "X", Long.MAX_VALUE / 2 + 1, 2).subtotalCents());
    }

    private static Order order(Order.Status status) {
        return new Order(UUID.randomUUID(), "alice", "k", ReservationId.of("r"), status, 1999, "USD", PLACED, DEADLINE,
                status == Order.Status.PENDING ? null : DEADLINE, List.of(new Order.Line("tessera", "Tessera", 1999, 1)));
    }
}
