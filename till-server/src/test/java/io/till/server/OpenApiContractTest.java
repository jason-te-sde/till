package io.till.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.till.core.IdempotencyKey;
import io.till.core.Sku;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * The committed OpenAPI document still describes this service.
 *
 * <p>{@code till-web} generates its TypeScript types from {@code till-web/openapi.json}, so that file
 * is the contract between the two halves of this project. A committed contract that has silently
 * stopped matching the code is worse than no contract at all: the frontend compiles against types
 * for an API that no longer exists, and finds out in a browser.
 *
 * <p>So this generates and guards in one place. Normally it compares; with
 * {@code -Dtill.openapi.write=true} it rewrites the file instead, which is the one command to run
 * after changing an endpoint. CI never passes that flag, so a forgotten regeneration is a red build
 * rather than a broken console.
 *
 * <p>Both sides are canonicalised — object keys sorted, two-space indent — because the document is
 * assembled from annotations by reflection and a change in field order is not a change in the
 * contract. Array order is left alone, because there it is meaningful.
 */
// Suites that share the one database run one at a time; see the note in the other API tests.
@ResourceLock("till-database")
class OpenApiContractTest extends ApiTestBase {

    /** Relative to {@code till-server}, which is where Surefire runs. */
    private static final Path COMMITTED = Path.of("..", "till-web", "openapi.json");

    private static final String WRITE = "till.openapi.write";

    @Autowired
    JsonMapper json;

    @Test
    @DisplayName("the committed openapi.json is what this service serves")
    void theContractIsCurrent() throws IOException, InterruptedException {
        String live = canonical(fetchSpec());

        if (Boolean.getBoolean(WRITE)) {
            Files.createDirectories(COMMITTED.getParent());
            Files.writeString(COMMITTED, live + "\n", StandardCharsets.UTF_8);
            System.out.println("wrote " + COMMITTED.toAbsolutePath().normalize());
            return;
        }

        assertTrue(
                Files.exists(COMMITTED),
                COMMITTED + " is missing. Generate it with:\n"
                        + "  mvn -pl till-server test -Dtest=OpenApiContractTest -Dtill.openapi.write=true "
                        + "-Dsurefire.failIfNoSpecifiedTests=false");

        assertEquals(
                Files.readString(COMMITTED, StandardCharsets.UTF_8).strip(),
                live,
                "the API has changed and " + COMMITTED + " has not. Regenerate it with:\n"
                        + "  mvn -pl till-server test -Dtest=OpenApiContractTest -Dtill.openapi.write=true "
                        + "-Dsurefire.failIfNoSpecifiedTests=false\n"
                        + "then `npm run api:types` in till-web, and commit both.");
    }

    @Test
    @DisplayName("every endpoint the console calls is in the document")
    void theConsolesEndpointsAreDescribed() throws IOException, InterruptedException {
        @SuppressWarnings("unchecked")
        Map<String, Object> paths = (Map<String, Object>) parse(fetchSpec()).get("paths");

        // Named individually rather than counted, so that removing one is a failure here instead of
        // a blank panel in the console.
        for (String path :
                List.of(
                        "/v1/stock",
                        "/v1/stock/{sku}",
                        "/v1/stock/{sku}/adjust",
                        "/v1/reservations",
                        "/v1/reservations/{id}",
                        "/v1/reservations/{id}/commit",
                        "/v1/reservations/{id}/release",
                        "/v1/outbox")) {
            assertTrue(paths.containsKey(path), path + " is not in the OpenAPI document");
        }
    }

    @Test
    @DisplayName("the declared Problem is the problem this service actually sends")
    @SuppressWarnings("unchecked")
    void theProblemSchemaMatchesARealFailure() throws IOException, InterruptedException {
        Map<String, Object> components = (Map<String, Object>) parse(fetchSpec()).get("components");
        Map<String, Object> schemas = (Map<String, Object>) components.get("schemas");
        Map<String, Object> problem = (Map<String, Object>) schemas.get("Problem");
        assertTrue(problem != null, "the contract declares no Problem schema");

        // A real refusal, read off the wire rather than through the client. Api.Problem is a second
        // declaration of a shape whose first declaration is the ProblemDetail that Problems
        // assembles, and two declarations of one shape drift. This is the thing that notices.
        admin().adjust(IdempotencyKey.of("d1"), Sku.of("widget"), 2);
        HttpResponse<String> refused =
                HttpClient.newHttpClient()
                        .send(
                                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v1/reservations"))
                                        .header("Authorization", "Bearer " + CLIENT_TOKEN)
                                        .header("Idempotency-Key", "c1")
                                        .header("Content-Type", "application/json")
                                        .POST(
                                                HttpRequest.BodyPublishers.ofString(
                                                        "{\"lines\":[{\"sku\":\"widget\",\"quantity\":5}]}"))
                                        .build(),
                                HttpResponse.BodyHandlers.ofString());
        assertEquals(409, refused.statusCode(), refused.body());

        Map<String, Object> declared = (Map<String, Object>) problem.get("properties");
        // Every field on the wire is declared. A body carrying something the contract does not
        // mention is how a client ends up parsing by hand, which is what the contract exists to stop.
        Map<String, Object> sent = parse(refused.body());
        for (String field : sent.keySet()) {
            assertTrue(declared.containsKey(field), "the service sends " + field + ", which the contract omits");
        }
        // And the other direction, which is the one that catches an over-promise: every field the
        // contract marks required is really on the wire. `type` is how this earns its keep — RFC 9457
        // makes `about:blank` the default and Spring omits the field rather than repeating it, so
        // declaring it required would have typed a generated client's `problem.type` as a string that
        // is in fact undefined.
        List<?> required = (List<?>) problem.get("required");
        for (Object field : required) {
            assertTrue(sent.containsKey(field), "the contract requires " + field + ", which is not in the body");
        }
        // And the fields a client branches on are really there.
        for (String field : List.of("status", "detail", "code", "shortfalls")) {
            assertTrue(sent.containsKey(field), "a refusal for stock did not carry " + field);
        }
        assertEquals("INSUFFICIENT_STOCK", sent.get("code"));
    }

    private String fetchSpec() throws IOException, InterruptedException {
        HttpResponse<String> response =
                HttpClient.newHttpClient()
                        .send(
                                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v3/api-docs"))
                                        .GET()
                                        .build(),
                                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        return response.body();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parse(String document) {
        return json.readValue(document, Map.class);
    }

    private String canonical(String document) {
        // Read into maps and write them back sorted. Sorting an ObjectNode is not something Jackson
        // will do; sorting a Map is, which is why this goes through Map rather than through a tree.
        return json.rebuild()
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .enable(SerializationFeature.INDENT_OUTPUT)
                .build()
                .writeValueAsString(parse(document))
                .strip();
    }
}
