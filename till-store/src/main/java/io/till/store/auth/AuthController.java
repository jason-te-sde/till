package io.till.store.auth;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.till.store.StoreProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.authentication.logout.CookieClearingLogoutHandler;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/** Who is signed in, and signing out. Signing in is Spring Security's own endpoint. */
@RestController
@Tag(name = "session", description = "The current session.")
class AuthController {

    private final String logoutTemplate;
    private final String clientId;

    AuthController(StoreProperties properties, ClientRegistrationRepository registrations) {
        this.logoutTemplate = properties.auth().oidc().logoutUri();
        this.clientId = registrations.findByRegistrationId(IdentityProvider.REGISTRATION).getClientId();
    }

    /**
     * Who is signed in, if anybody.
     *
     * <p>200 with {@code authenticated: false} for a visitor rather than a 401, because being signed
     * out is a normal state for a store page, not an error — and a 401 on every anonymous page load
     * would be noise in every log and every browser console.
     */
    @Operation(operationId = "getSession", summary = "Who is signed in")
    @GetMapping("/api/me")
    ResponseEntity<Me> me(@AuthenticationPrincipal OidcUser user, Authentication authentication) {
        Me me = user == null
                ? new Me(false, null, null, false)
                : new Me(true, displayName(user), user.getEmail(), isAdmin(authentication));
        // Per user, so never shared by a proxy, and never served from a cache after signing out.
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(me);
    }

    /**
     * Ends the session here, and says where to end it at the identity provider.
     *
     * <p>A POST, and CSRF-protected like every other write: a GET that logs people out is a page any
     * other site can embed to do it for them. The answer is JSON rather than a redirect, because the
     * SPA calls this with {@code fetch}, and a fetch cannot follow a redirect to another origin's logout
     * page — so it is told the address and navigates there itself.
     */
    @Operation(operationId = "logout", summary = "Sign out")
    @PostMapping("/api/logout")
    Logout logout(
            HttpServletRequest request,
            HttpServletResponse response,
            Authentication authentication,
            @AuthenticationPrincipal OidcUser user) {
        String redirect = providerLogout(request, user);
        new SecurityContextLogoutHandler().logout(request, response, authentication);
        new CookieClearingLogoutHandler("SESSION", "XSRF-TOKEN").logout(request, response, authentication);
        return new Logout(redirect);
    }

    /**
     * The provider's logout address, from the configured template — or this site's home page when
     * there is none, which ends the session here and leaves the provider's own session alone.
     */
    private String providerLogout(HttpServletRequest request, OidcUser user) {
        String home = ServletUriComponentsBuilder.fromContextPath(request).path("/").build().toUriString();
        if (logoutTemplate.isBlank()) {
            return home;
        }
        String idToken = user == null || user.getIdToken() == null ? "" : user.getIdToken().getTokenValue();
        return logoutTemplate
                .replace("{clientId}", encode(clientId))
                .replace("{baseUrl}", encode(home.endsWith("/") ? home.substring(0, home.length() - 1) : home))
                .replace("{idToken}", encode(idToken));
    }

    private static String displayName(OidcUser user) {
        for (String candidate : new String[] {user.getFullName(), user.getGivenName(), user.getPreferredUsername(), user.getEmail()}) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate;
            }
        }
        return "Player";
    }

    private static boolean isAdmin(Authentication authentication) {
        return authentication != null
                && authentication.getAuthorities().stream()
                        .anyMatch(authority -> IdentityProvider.ROLE_ADMIN.equals(authority.getAuthority()));
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /**
     * The current session.
     *
     * @param authenticated whether anybody is signed in
     * @param name what to call them, or null for a visitor
     * @param email their email, or null
     * @param admin whether they may use the operator console
     */
    @Schema(name = "Me")
    record Me(
            boolean authenticated,
            @Schema(nullable = true) String name,
            @Schema(nullable = true) String email,
            boolean admin) {}

    /**
     * @param redirect where to navigate to finish signing out
     */
    @Schema(name = "Logout")
    record Logout(String redirect) {}
}
