package io.till.catalogue;

/** The catalogue's own database would not answer. Unchecked; the handler turns it into a 503. */
class CatalogueException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    CatalogueException(String message, Throwable cause) {
        super(message, cause);
    }
}
