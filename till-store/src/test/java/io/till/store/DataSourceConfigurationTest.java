package io.till.store;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.zaxxer.hikari.HikariConfig;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/**
 * {@code spring.datasource.hikari.data-source-properties.loginTimeout} binds from this module's
 * real {@code application.yml} — ten seconds by default, overridable by {@code
 * STORE_DB_LOGIN_TIMEOUT} — the same property {@code io.till.server.LoginTimeoutTest} proves
 * actually bounds a slow login against a real, deliberately slowed-down database. A binding-only
 * proof is enough here: a second Testcontainers-and-event-trigger suite would exercise the
 * identical Hikari/pgjdbc mechanism against the same kind of database, proving nothing that suite
 * did not already prove.
 */
class DataSourceConfigurationTest {

    @Test
    @DisplayName("defaults to ten seconds")
    void defaultsToTenSeconds() throws IOException {
        assertEquals("10", loginTimeout(Map.of()));
    }

    @Test
    @DisplayName("STORE_DB_LOGIN_TIMEOUT overrides it, the same way it would as an environment variable")
    void overriddenByTheEnvironmentVariable() throws IOException {
        assertEquals("30", loginTimeout(Map.of("STORE_DB_LOGIN_TIMEOUT", "30")));
    }

    /**
     * Binds {@code spring.datasource.hikari.*} from the real {@code application.yml} onto a fresh
     * {@link HikariConfig}, exactly as Spring Boot itself would, and reads back the resolved
     * {@code loginTimeout} data source property.
     *
     * @param environmentOverrides stand in for an environment variable such as {@code
     *     STORE_DB_LOGIN_TIMEOUT}: layered with higher precedence than the packaged file, the same
     *     relationship a real environment variable has to it
     */
    private static String loginTimeout(Map<String, String> environmentOverrides) throws IOException {
        List<PropertySource<?>> loaded =
                new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"));
        MutablePropertySources propertySources = new MutablePropertySources();
        loaded.forEach(propertySources::addLast);
        if (!environmentOverrides.isEmpty()) {
            propertySources.addFirst(new MapPropertySource("test-environment", Map.copyOf(environmentOverrides)));
        }
        Binder binder =
                new Binder(
                        ConfigurationPropertySources.from(propertySources),
                        new PropertySourcesPlaceholdersResolver(propertySources));
        HikariConfig config = new HikariConfig();
        binder.bind("spring.datasource.hikari", Bindable.ofInstance(config));
        return config.getDataSourceProperties().getProperty("loginTimeout");
    }
}
