package io.till.server;

import jakarta.annotation.PostConstruct;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Serves the browser console, if one was built into this jar.
 *
 * <p>The console is a single-page application, so a reload of {@code /ops/reservations} arrives here
 * as a request for a path no controller has. The usual answer is a catch-all that forwards
 * everything to {@code index.html}, and the usual bug that comes with it is that the catch-all also
 * swallows {@code /v1/nonsense} and turns a 404 from the API into an HTML page — which a client
 * parses as JSON and reports as a corrupt response.
 *
 * <p>So the forward is <b>enumerated</b> rather than catch-all. Only the routes the console actually
 * has are forwarded; everything else, including every mistyped API path, falls through to Spring's
 * own 404.
 *
 * <p>Built with {@code mvn -Pweb package}. Without that profile the jar has no console and these
 * routes answer 404, which is said out loud at startup rather than left to be discovered.
 */
@Configuration
class WebUi implements WebMvcConfigurer {

    private static final Logger LOG = LoggerFactory.getLogger(WebUi.class);

    private final TillProperties properties;

    WebUi(TillProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    void announce() {
        if (new ClassPathResource("static/index.html").exists()) {
            LOG.info("browser console available at / (shop) and /ops");
        } else {
            LOG.info("no browser console in this build; rebuild with -Pweb to include it");
        }
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        List<String> origins = properties.web().corsOrigins();
        if (origins.isEmpty()) {
            return;
        }
        LOG.info("allowing browser calls to /v1 from {}", origins);
        registry.addMapping("/v1/**")
                .allowedOrigins(origins.toArray(String[]::new))
                .allowedMethods("GET", "POST")
                .allowedHeaders("Authorization", "Content-Type", "Idempotency-Key")
                .maxAge(3600);
    }

    /** Forwards the console's own routes to its entry point. */
    @Controller
    static class Routes {

        @GetMapping({"/", "/shop", "/shop/**", "/ops", "/ops/**"})
        String console() {
            return "forward:/index.html";
        }
    }
}
