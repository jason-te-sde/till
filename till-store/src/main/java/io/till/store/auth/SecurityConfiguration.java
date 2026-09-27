package io.till.store.auth;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.session.autoconfigure.DefaultCookieSerializerCustomizer;
import org.springframework.boot.web.server.autoconfigure.ServerProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.authority.mapping.GrantedAuthoritiesMapper;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.HttpSessionOAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * The store is a backend-for-frontend, and this is most of what that means.
 *
 * <h2>The browser never holds a token</h2>
 *
 * <p>Sign-in is the OpenID Connect authorization-code flow with PKCE, run here, on the server. The
 * code is exchanged here, the tokens stay in the server-side session, and the browser is given a
 * session cookie it cannot read from script ({@code HttpOnly}) and does not send cross-site
 * ({@code SameSite=Lax}). A token in {@code localStorage} is a token any injected script can post
 * anywhere; a cookie the page cannot read is not.
 *
 * <h2>Which is why CSRF protection is on</h2>
 *
 * <p>A cookie the browser attaches automatically is exactly what CSRF exploits, so every state-changing
 * request must also carry the {@code X-XSRF-TOKEN} header, copied from a cookie only a script on this
 * origin can read. {@link SpaCsrfTokenHandler} reads it the way an SPA sends it, and
 * {@link CsrfCookieFilter} makes sure the cookie exists before the first write needs it — without ever
 * setting it on a response a shared cache may store.
 *
 * <h2>Sessions live in Redis</h2>
 *
 * <p>So does the authorized client — the tokens — which is why it is configured explicitly below.
 * Spring's default keeps authorized clients in a map in this JVM, and a second instance behind the
 * proxy would then have a session that says "signed in" and no tokens to go with it.
 */
@Configuration
@EnableWebSecurity
class SecurityConfiguration {

    @Bean
    SecurityFilterChain storeSecurity(
            HttpSecurity http,
            ClientRegistrationRepository registrations,
            GrantedAuthoritiesMapper groupRoles,
            ProblemResponses problems)
            throws Exception {
        RequestMatcher api = PathPatternRequestMatcher.withDefaults().matcher("/api/**");
        // The catalogue: the same for every visitor, and marked cacheable by any cache in front.
        PathPatternRequestMatcher.Builder paths = PathPatternRequestMatcher.withDefaults();
        RequestMatcher catalogue = new OrRequestMatcher(
                paths.matcher(HttpMethod.GET, "/api/games"),
                paths.matcher(HttpMethod.GET, "/api/games/*"),
                paths.matcher(HttpMethod.GET, "/api/home"),
                paths.matcher(HttpMethod.GET, "/api/genres"));
        http.authorizeHttpRequests(auth -> auth
                        // Browsing is for everybody.
                        .requestMatchers(catalogue).permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/me").permitAll()
                        // The operator console, for the admin group only.
                        .requestMatchers("/api/ops/**").hasRole("ADMIN")
                        // Everything else in the API needs a signed-in customer.
                        .requestMatchers("/api/**").authenticated()
                        // The sign-in round trip and the API documentation.
                        .anyRequest().permitAll())
                .oauth2Login(login -> login
                        .authorizationEndpoint(endpoint -> endpoint.authorizationRequestResolver(resolver(registrations)))
                        .userInfoEndpoint(userInfo -> userInfo.userAuthoritiesMapper(groupRoles))
                        .authorizedClientRepository(new HttpSessionOAuth2AuthorizedClientRepository())
                        .successHandler((request, response, authentication) ->
                                response.sendRedirect(ReturnTo.consume(request)))
                        // A failed sign-in lands on the store with a flag the SPA turns into a message,
                        // rather than on a framework error page with a stack of OAuth parameters in it.
                        .failureHandler((request, response, exception) ->
                                response.sendRedirect("/?signin=failed")))
                // Our own logout endpoint, which answers JSON: a fetch cannot follow the redirect to the
                // provider's logout page, so the SPA is told where to navigate instead.
                .logout(logout -> logout.disable())
                .csrf(csrf -> csrf
                        // Readable from script, so the SPA can copy it into the X-XSRF-TOKEN header.
                        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .csrfTokenRequestHandler(new SpaCsrfTokenHandler()))
                .addFilterAfter(new CsrfCookieFilter(catalogue), CsrfFilter.class)
                // No saved-request redirects: an API call without a session is answered 401, and where
                // to return after sign-in is the explicit, checked returnTo instead.
                .requestCache(cache -> cache.disable())
                .exceptionHandling(errors -> errors
                        .defaultAuthenticationEntryPointFor(problems::unauthorized, api)
                        .defaultAccessDeniedHandlerFor(problems::forbidden, api));
        return http.build();
    }

    /**
     * The session cookie's flags, whichever way the application is deployed.
     *
     * <p>Spring Boot copies {@code server.servlet.session.cookie.*} onto Spring Session's cookie only
     * when it runs its own embedded server. In any other shape — a WAR, or the mock servlet environment
     * the tests run in — it copies the servlet container's defaults instead, and those are not
     * {@code HttpOnly}. That cookie is the whole of a customer's authentication here, so {@code HttpOnly}
     * and {@code SameSite=Lax} are fixed in code rather than left to which branch of an
     * auto-configuration happened to run. Only {@code Secure} is configuration, because the local stack
     * is plain HTTP.
     *
     * @param server where {@code server.servlet.session.cookie.secure} is read from
     * @return the customizer Spring Boot applies to the cookie in every deployment shape
     */
    @Bean
    DefaultCookieSerializerCustomizer sessionCookieFlags(ServerProperties server) {
        boolean secure = Boolean.TRUE.equals(server.getServlet().getSession().getCookie().getSecure());
        return serializer -> {
            serializer.setUseHttpOnlyCookie(true);
            serializer.setSameSite("Lax");
            serializer.setUseSecureCookie(secure);
        };
    }

    /** PKCE, and remembering where the customer was going. */
    private static OAuth2AuthorizationRequestResolver resolver(ClientRegistrationRepository registrations) {
        DefaultOAuth2AuthorizationRequestResolver delegate =
                new DefaultOAuth2AuthorizationRequestResolver(registrations, "/oauth2/authorization");
        // PKCE for a confidential client too. The client secret already proves who is exchanging the
        // code; PKCE proves it is the same party that started the flow, which stops an intercepted
        // authorization code being redeemed by anybody else.
        delegate.setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce());
        return new OAuth2AuthorizationRequestResolver() {
            @Override
            public OAuth2AuthorizationRequest resolve(HttpServletRequest request) {
                return remembering(request, delegate.resolve(request));
            }

            @Override
            public OAuth2AuthorizationRequest resolve(HttpServletRequest request, String registrationId) {
                return remembering(request, delegate.resolve(request, registrationId));
            }
        };
    }

    private static OAuth2AuthorizationRequest remembering(HttpServletRequest request, OAuth2AuthorizationRequest resolved) {
        if (resolved != null) {
            ReturnTo.remember(request);
        }
        return resolved;
    }
}
