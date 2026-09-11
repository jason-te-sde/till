package io.till.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
                java.util.List.of(
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
