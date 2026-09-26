package io.till.store;

/** The catalogue's own database would not answer. Unchecked; the handler turns it into a 503. */
class StoreException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    StoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
