package io.till.server;

/** Something was looked up by id and is not there. */
class NotFoundException extends RuntimeException {

    private final String title;

    NotFoundException(String title, String detail) {
        super(detail);
        this.title = title;
    }

    String title() {
        return title;
    }
}
