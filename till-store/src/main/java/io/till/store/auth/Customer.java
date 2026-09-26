package io.till.store.auth;

import org.springframework.security.oauth2.core.oidc.user.OidcUser;

/** Who a signed-in request belongs to. */
public final class Customer {

    private Customer() {}

    /**
     * The customer's stable identifier: the OIDC subject.
     *
     * <p>The subject rather than the email, because a subject is issued once and never reassigned,
     * while an email can be changed — and can later belong to somebody else. An order history keyed by
     * email follows the address, not the person.
     *
     * @param user the signed-in principal
     * @return its subject
     * @throws IllegalStateException if there is no principal, which only a misconfigured endpoint —
     *     one that forgot to require sign-in — could produce
     */
    public static String id(OidcUser user) {
        if (user == null || user.getSubject() == null) {
            throw new IllegalStateException("this endpoint needs a signed-in customer and has none");
        }
        return user.getSubject();
    }
}
