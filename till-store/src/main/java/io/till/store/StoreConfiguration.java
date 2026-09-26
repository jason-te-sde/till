package io.till.store;

import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.servers.Server;
import io.till.store.web.Problem;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The clock, and the published contract. */
@Configuration
class StoreConfiguration {

    /**
     * A bean rather than {@code Instant.now()}, so a test can hold time still and assert that a hold
     * expires exactly when it should rather than roughly.
     *
     * @return the system clock, in UTC
     */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * @return the document's metadata, with a relative server URL — an absolute one would be whatever
     *     host answered the request that generated it, which behind a proxy is nobody's real address
     */
    @Bean
    OpenAPI storeOpenApi() {
        return new OpenAPI()
                .servers(List.of(new Server().url("/").description("this store")))
                .info(new Info()
                        .title("till store")
                        .version("0.2.0")
                        .description("The game store's API: browsing, checkout, orders, and the operator console's "
                                + "backend. Signed-in endpoints use a session cookie and require an X-XSRF-TOKEN "
                                + "header on every write; every write also takes an Idempotency-Key.")
                        .license(new License().name("MIT").url("https://opensource.org/licenses/MIT")));
    }

    /**
     * Finishes the published contract: declares what an error looks like, then marks what is always
     * present as required.
     *
     * <p><b>Errors.</b> Every operation gains a {@code default} response of {@link Problem}, served as
     * {@code application/problem+json} — so the generated client knows that a failure has a
     * {@code code} to branch on without every controller method repeating it in annotations.
     *
     * <p><b>Required fields.</b> Jackson writes every field of these records on every response, null or
     * not, so every one of them is always present — and saying so is what lets the generated TypeScript
     * type them as present. Left alone, springdoc infers nothing from a record and publishes every field
     * as optional, and every call site in the browser then checks for a case that cannot happen. A field
     * that can be null says so with {@code nullable}, which the generator turns into {@code | null}.
     * {@link Problem} is the exception: its optional fields really are sometimes absent, and it says
     * which itself.
     *
     * <p>One customizer rather than two, because the second step must see the schemas the first adds,
     * and an order between beans is one more thing to get wrong.
     *
     * @return the customizer
     */
    @Bean
    OpenApiCustomizer contract() {
        return openApi -> {
            declareProblems(openApi);
            markPresentFieldsRequired(openApi);
        };
    }

    private static void declareProblems(OpenAPI openApi) {
        if (openApi.getComponents() == null) {
            openApi.setComponents(new Components());
        }
        ModelConverters.getInstance().readAll(Problem.class).forEach(openApi.getComponents()::addSchemas);
        ApiResponse failure = new ApiResponse()
                .description("Refused or failed; `code` says why.")
                .content(new Content().addMediaType(
                        "application/problem+json",
                        new MediaType().schema(new Schema<>().$ref("#/components/schemas/Problem"))));
        if (openApi.getPaths() != null) {
            openApi.getPaths().values().forEach(path -> path.readOperations()
                    .forEach(operation -> operation.getResponses().addApiResponse("default", failure)));
        }
    }

    private static void markPresentFieldsRequired(OpenAPI openApi) {
        if (openApi.getComponents() == null || openApi.getComponents().getSchemas() == null) {
            return;
        }
        // The components map is raw in swagger-core's API; each value is read back as a Schema<?>.
        for (String name : openApi.getComponents().getSchemas().keySet()) {
            Schema<?> schema = openApi.getComponents().getSchemas().get(name);
            if (!"Problem".equals(name) && schema.getProperties() != null && !schema.getProperties().isEmpty()) {
                schema.setRequired(new ArrayList<>(schema.getProperties().keySet()));
            }
        }
    }
}
