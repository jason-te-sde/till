package io.till.store.auth;

import io.till.store.StoreProperties;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.authority.mapping.GrantedAuthoritiesMapper;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.ClientRegistrations;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.oidc.IdTokenClaimNames;
import org.springframework.security.oauth2.core.oidc.user.OidcUserAuthority;

/**
 * Who signs customers in, and what their groups mean here.
 *
 * <p>Built from the store's own properties rather than Spring Boot's {@code spring.security.oauth2.*}
 * block, for one reason: the local stack needs an issuer that is checked but not fetched. In a
 * container network the browser reaches the provider at one address and this service at another, so a
 * discovery document cannot be right for both — see {@link StoreProperties.Oidc}. Boot's properties
 * either fetch the issuer or skip checking it; this does neither.
 */
@Configuration
class IdentityProvider {

    /** The registration id, and so part of every sign-in URL: {@code /oauth2/authorization/idp}. */
    static final String REGISTRATION = "idp";

    static final String ROLE_CUSTOMER = "ROLE_CUSTOMER";
    static final String ROLE_ADMIN = "ROLE_ADMIN";

    /**
     * @param properties the provider's settings
     * @return the one registration this store signs in with
     */
    @Bean
    ClientRegistrationRepository clientRegistrations(StoreProperties properties) {
        StoreProperties.Oidc oidc = properties.auth().oidc();
        if (oidc.issuerUri().isBlank() || oidc.clientId().isBlank()) {
            throw new IllegalStateException(
                    "store.auth.oidc.issuer-uri and store.auth.oidc.client-id are required; the store cannot "
                            + "sign anybody in without an identity provider");
        }
        ClientRegistration.Builder builder;
        if (oidc.explicit()) {
            // Checked, not fetched: issuerUri is compared with every ID token's `iss` by Spring's own
            // validator, which reads it from here.
            builder = ClientRegistration.withRegistrationId(REGISTRATION)
                    .issuerUri(oidc.issuerUri())
                    .authorizationUri(oidc.authorizationUri())
                    .tokenUri(oidc.tokenUri())
                    .jwkSetUri(oidc.jwkSetUri())
                    .userNameAttributeName(IdTokenClaimNames.SUB);
            if (!oidc.userInfoUri().isBlank()) {
                builder.userInfoUri(oidc.userInfoUri());
            }
        } else {
            builder = ClientRegistrations.fromIssuerLocation(oidc.issuerUri()).registrationId(REGISTRATION);
        }
        ClientRegistration registration = builder
                .clientId(oidc.clientId())
                .clientSecret(oidc.clientSecret())
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                // {baseUrl} resolves from the forwarded headers the proxy sets, so the callback is the
                // public address — not the container's own, which the browser cannot reach.
                .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                .scope(oidc.scopes())
                .clientName("Sign in")
                .build();
        return new InMemoryClientRegistrationRepository(registration);
    }

    /**
     * Turns the provider's group claim into this store's roles.
     *
     * <p>Every signed-in user is a customer; members of the admin group are also operators. The claim
     * is read from both the ID token and the user-info response, because providers differ about which
     * one carries it — Cognito puts {@code cognito:groups} in the ID token, others only in user info.
     *
     * @param properties which claim, and which group
     * @return the mapper sign-in applies once, when the session is created
     */
    @Bean
    GrantedAuthoritiesMapper groupRoles(StoreProperties properties) {
        String claim = properties.auth().groupsClaim();
        String adminGroup = properties.auth().adminGroup();
        return authorities -> {
            Set<GrantedAuthority> roles = new HashSet<>(authorities);
            roles.add(new SimpleGrantedAuthority(ROLE_CUSTOMER));
            for (GrantedAuthority authority : authorities) {
                if (authority instanceof OidcUserAuthority oidc) {
                    List<String> groups = new ArrayList<>(groups(oidc.getIdToken().getClaims(), claim));
                    if (oidc.getUserInfo() != null) {
                        groups.addAll(groups(oidc.getUserInfo().getClaims(), claim));
                    }
                    if (groups.contains(adminGroup)) {
                        roles.add(new SimpleGrantedAuthority(ROLE_ADMIN));
                    }
                }
            }
            return roles;
        };
    }

    /** A JSON array in every provider seen so far, but a lone string is accepted rather than ignored. */
    private static List<String> groups(Map<String, Object> claims, String name) {
        Object value = claims.get(name);
        if (value instanceof Collection<?> values) {
            return values.stream().map(String::valueOf).toList();
        }
        if (value instanceof String single && !single.isBlank()) {
            return List.of(single);
        }
        return List.of();
    }
}
