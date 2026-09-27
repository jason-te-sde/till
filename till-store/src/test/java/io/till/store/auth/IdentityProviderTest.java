package io.till.store.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.till.store.StoreProperties;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.oauth2.core.oidc.user.OidcUserAuthority;

class IdentityProviderTest {

    private final IdentityProvider provider = new IdentityProvider();

    @Test
    @DisplayName("everybody who signs in is a customer")
    void everybodyIsACustomer() {
        assertEquals(Set.of("ROLE_CUSTOMER", "OIDC_USER"), roles(Map.of(), null));
    }

    @Test
    @DisplayName("members of the admin group are operators too — from the ID token, as Cognito sends it")
    void adminFromIdToken() {
        assertTrue(roles(Map.of("cognito:groups", List.of("players", "admins")), null).contains("ROLE_ADMIN"));
    }

    @Test
    @DisplayName("or from the user-info response, where other providers put it")
    void adminFromUserInfo() {
        assertTrue(roles(Map.of(), Map.of("cognito:groups", List.of("admins"))).contains("ROLE_ADMIN"));
    }

    @Test
    @DisplayName("a lone string is read as one group, not ignored")
    void singleGroup() {
        assertTrue(roles(Map.of("cognito:groups", "admins"), null).contains("ROLE_ADMIN"));
    }

    @Test
    @DisplayName("any other group, or a near miss, is not the admin group")
    void notAdmin() {
        assertEquals(false, roles(Map.of("cognito:groups", List.of("players", "Admins", "admins ")), null).contains("ROLE_ADMIN"));
        assertEquals(false, roles(Map.of("groups", List.of("admins")), null).contains("ROLE_ADMIN"), "only the configured claim counts");
    }

    @Test
    @DisplayName("the claim and the group are configuration, for providers that name them differently")
    void configurable() {
        StoreProperties properties = properties(Map.of("store.auth.groups-claim", "groups", "store.auth.admin-group", "ops"));
        Collection<? extends GrantedAuthority> mapped = provider.groupRoles(properties)
                .mapAuthorities(List.of(authority(Map.of("groups", List.of("ops")), null)));
        assertTrue(names(mapped).contains("ROLE_ADMIN"));
    }

    @Test
    @DisplayName("with explicit endpoints, nothing is fetched and the issuer is still checked")
    void explicitEndpoints() {
        ClientRegistration registration = provider.clientRegistrations(properties(Map.of(
                        "store.auth.oidc.issuer-uri", "http://localhost:8180/realms/till",
                        "store.auth.oidc.client-id", "till-store",
                        "store.auth.oidc.client-secret", "secret",
                        "store.auth.oidc.authorization-uri", "http://localhost:8180/realms/till/protocol/openid-connect/auth",
                        "store.auth.oidc.token-uri", "http://keycloak:8080/realms/till/protocol/openid-connect/token",
                        "store.auth.oidc.jwk-set-uri", "http://keycloak:8080/realms/till/protocol/openid-connect/certs")))
                .findByRegistrationId(IdentityProvider.REGISTRATION);

        // The browser's address for the login page, the container network's for the code exchange —
        // and the issuer every ID token must name, which is the browser's.
        assertEquals("http://localhost:8180/realms/till", registration.getProviderDetails().getIssuerUri());
        assertEquals("http://keycloak:8080/realms/till/protocol/openid-connect/token", registration.getProviderDetails().getTokenUri());
        assertEquals("{baseUrl}/login/oauth2/code/{registrationId}", registration.getRedirectUri());
        assertEquals(Set.of("openid", "profile", "email"), registration.getScopes());
    }

    @Test
    @DisplayName("refuses to start without a provider, and says which settings are missing")
    void refusesToStartWithout() {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> provider.clientRegistrations(properties(Map.of())));
        assertTrue(e.getMessage().contains("store.auth.oidc.issuer-uri"), e.getMessage());
    }

    private Set<String> roles(Map<String, Object> idTokenClaims, Map<String, Object> userInfoClaims) {
        return names(provider.groupRoles(properties(Map.of()))
                .mapAuthorities(List.of(authority(idTokenClaims, userInfoClaims))));
    }

    private static OidcUserAuthority authority(Map<String, Object> idTokenClaims, Map<String, Object> userInfoClaims) {
        Map<String, Object> claims = new HashMap<>(idTokenClaims);
        claims.put("sub", "someone");
        OidcIdToken token = new OidcIdToken("id-token", null, null, claims);
        OidcUserInfo userInfo = userInfoClaims == null ? null : new OidcUserInfo(withSubject(userInfoClaims));
        return new OidcUserAuthority(token, userInfo);
    }

    private static Map<String, Object> withSubject(Map<String, Object> claims) {
        Map<String, Object> copy = new HashMap<>(claims);
        copy.put("sub", "someone");
        return copy;
    }

    private static Set<String> names(Collection<? extends GrantedAuthority> authorities) {
        return authorities.stream().map(GrantedAuthority::getAuthority).collect(Collectors.toSet());
    }

    /** Bound the way Spring Boot binds them, defaults and all. */
    private static StoreProperties properties(Map<String, String> values) {
        return new Binder(new MapConfigurationPropertySource(values)).bindOrCreate("store", StoreProperties.class);
    }
}
