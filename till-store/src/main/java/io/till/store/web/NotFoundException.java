package io.till.store.web;

/** Something looked up by id that is not there — or not the caller's, which answers the same. */
public class NotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String title;

    /**
     * @param title a short name for what is missing, for the problem's {@code title}
     * @param detail the sentence naming the specific thing
     */
    public NotFoundException(String title, String detail) {
        super(detail);
        this.title = title;
    }

    /**
     * @return a short name for what is missing
     */
    public String title() {
        return title;
    }
}
