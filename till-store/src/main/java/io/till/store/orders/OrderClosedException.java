package io.till.store.orders;

/**
 * An action on an order that has already finished, one way or another.
 *
 * <p>Carries the order's status so the response can say which: paying for an expired order is a
 * {@code 410 Gone} — it existed, the customer had it, and it is no longer available — while paying for
 * a cancelled one is a {@code 409}, a conflict with something the customer did themselves.
 */
public class OrderClosedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final Order.Status status;

    /**
     * @param status where the order stands
     * @param message what to tell the customer
     */
    public OrderClosedException(Order.Status status, String message) {
        super(message);
        this.status = status;
    }

    /**
     * @return where the order stands
     */
    public Order.Status status() {
        return status;
    }
}
