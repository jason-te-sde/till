package io.till.server;

import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Makes the published contract tell the truth about failures.
 *
 * <p>Left alone, springdoc gives every declared response the schema of the method's return type. So
 * {@code POST /v1/reservations} published <i>404, 409, 422 and 503 all returning a {@code Reserved}</i>
 * — which is not merely undocumented but actively wrong, and the generated TypeScript said so too.
 * A client written against that contract would destructure {@code id} off a problem body.
 *
 * <p>Done here, once, rather than as an annotation on each of the fifteen error responses. The
 * repetition would be the kind that is right on the day it is written and wrong the first time
 * somebody adds an endpoint and copies the four lines above it without the fifth.
 *
 * <p>{@code 400} and {@code 401} are added to every operation for the same reason: they are reachable
 * everywhere — a malformed identifier, a missing or wrong token — and a contract that omits them
 * implies they cannot happen.
 */
@Configuration
class OpenApiProblems {

    private static final String MEDIA_TYPE = "application/problem+json";
    private static final String REF = "#/components/schemas/Problem";

    /**
     * @return a customizer that registers the problem schema and points every failure at it
     */
    @Bean
    OpenApiCustomizer problemResponses() {
        return openApi -> {
            register(openApi.getComponents());
            openApi.getPaths()
                    .values()
                    .forEach(path -> path.readOperations().forEach(operation -> {
                        ApiResponses responses = operation.getResponses();
                        responses.forEach((code, response) -> {
                            if (!code.startsWith("2")) {
                                response.setContent(problemContent());
                            }
                        });
                        addIfAbsent(responses, "400", "the request could not be read, or a value in it was not valid");
                        addIfAbsent(responses, "401", "no bearer token, or one this service does not know");
                    }));
        };
    }

    /**
     * Resolves {@link Api.Problem} the way springdoc resolves everything else, so the published
     * schema carries the annotations on the record rather than a hand-built approximation that would
     * have to be kept in step with them.
     */
    private static void register(Components components) {
        // `var` because swagger's converter hands back a raw map and there is nothing useful to
        // parameterise it with; writing the raw type out is what the build refuses.
        var schemas = ModelConverters.getInstance().readAll(Api.Problem.class);
        schemas.forEach(components::addSchemas);
    }

    private static Content problemContent() {
        return new Content().addMediaType(MEDIA_TYPE, new MediaType().schema(new Schema<>().$ref(REF)));
    }

    /**
     * Never overwrites. An operation that declares its own 400 means something more specific by it
     * than "the request was not valid", and that sentence is the useful one.
     */
    private static void addIfAbsent(ApiResponses responses, String code, String description) {
        if (!responses.containsKey(code)) {
            responses.addApiResponse(code, new ApiResponse().description(description).content(problemContent()));
        }
    }
}
