package io.till.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.RequestBuilder;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * The committed OpenAPI document still describes this service, and its errors are the errors it sends.
 *
 * <p>{@code till-web} generates its TypeScript types from {@code openapi/store.json}, so that file is
 * the contract between the store and its SPA. A committed contract that has silently stopped matching
 * the code is worse than none: the SPA compiles against types for an API that no longer exists, and
 * finds out in a browser.
 *
 * <p>Normally this compares; with {@code -Dtill.openapi.write=true} it rewrites the file instead —
 * the one command to run after changing an endpoint. CI never passes the flag, so a forgotten
 * regeneration is a red build rather than a broken page. Both sides are canonicalised (keys sorted,
 * two-space indent), because the document is assembled by reflection and field order is not contract.
 */
class OpenApiContractTest extends StoreTest {

    /** Relative to {@code till-store}, which is where Surefire runs. */
    private static final Path COMMITTED = Path.of("..", "openapi", "store.json");

    private static final String REGENERATE = "  mvn -pl till-store -am test -Dtest=OpenApiContractTest "
            + "-Dtill.openapi.write=true -Dsurefire.failIfNoSpecifiedTests=false";

    @Autowired
    JsonMapper json;

    @Test
    @DisplayName("the committed openapi/store.json is what this service serves")
    void theContractIsCurrent() throws Exception {
        String live = canonical(spec());

        if (Boolean.getBoolean("till.openapi.write")) {
            Files.createDirectories(COMMITTED.getParent());
            Files.writeString(COMMITTED, live + "\n", StandardCharsets.UTF_8);
            System.out.println("wrote " + COMMITTED.toAbsolutePath().normalize());
            return;
        }

        assertTrue(Files.exists(COMMITTED), COMMITTED + " is missing. Generate it with:\n" + REGENERATE);
        assertEquals(
                Files.readString(COMMITTED, StandardCharsets.UTF_8).strip(),
                live,
                "the API has changed and " + COMMITTED + " has not. Regenerate it with:\n" + REGENERATE
                        + "\nthen `npm run api:types` in till-web, and commit both.");
    }

    @Test
    @DisplayName("every endpoint the SPA calls is in the document")
    void theSpasEndpointsAreDescribed() throws Exception {
        Map<String, Object> paths = object(parse(spec()), "paths");

        // Named one by one rather than counted, so removing one fails here, not as a blank page.
        for (String path : List.of(
                "/api/home",
                "/api/games",
                "/api/games/{sku}",
                "/api/genres",
                "/api/me",
                "/api/logout",
                "/api/orders",
                "/api/orders/{id}",
                "/api/orders/{id}/pay",
                "/api/orders/{id}/cancel",
                "/api/ops/stock",
                "/api/ops/stock/{sku}/adjust",
                "/api/ops/reservations",
                "/api/ops/outbox")) {
            assertTrue(paths.containsKey(path), path + " is not in the OpenAPI document");
        }
    }

    @Test
    @DisplayName("every operation declares that it can fail, with the Problem shape")
    void everyOperationCanFail() throws Exception {
        Map<String, Object> paths = object(parse(spec()), "paths");
        for (Map.Entry<String, Object> path : paths.entrySet()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> operations = (Map<String, Object>) path.getValue();
            for (Map.Entry<String, Object> operation : operations.entrySet()) {
                @SuppressWarnings("unchecked")
                Map<String, Object> responses = (Map<String, Object>) ((Map<String, Object>) operation.getValue()).get("responses");
                assertTrue(responses.containsKey("default"), operation.getKey() + " " + path.getKey() + " declares no failure");
            }
        }
    }

    @Test
    @DisplayName("each kind of failure — the ledger's, validation's, security's, the framework's, a bug — is the declared Problem")
    void everyFailureIsTheDeclaredProblem() throws Exception {
        Map<String, Object> problem = object(object(object(parse(spec()), "components"), "schemas"), "Problem");
        Map<String, Object> declared = object(problem, "properties");
        List<?> required = (List<?>) problem.get("required");

        ledger.stock("tessera", 1);
        Map<String, RequestBuilder> failures = new LinkedHashMap<>();
        // The ledger's refusal, with shortfalls.
        failures.put("INSUFFICIENT_STOCK", placeOrder("{\"lines\":[{\"sku\":\"tessera\",\"quantity\":5}]}"));
        // Bean validation, with every field that failed.
        failures.put("INVALID_BODY", placeOrder("{\"lines\":[{\"sku\":\"\",\"quantity\":0}]}"));
        // The security layer, which answers before any controller runs.
        failures.put("UNAUTHORIZED", get("/api/orders"));
        failures.put("CSRF", post("/api/logout").with(customer("alice")));
        // The framework's own: a method the path does not have, and a body that is not JSON.
        failures.put("METHOD_NOT_ALLOWED", put("/api/orders").with(customer("alice")).with(csrfToken()));
        failures.put("BAD_REQUEST", post("/api/orders")
                .with(customer("alice"))
                .with(csrfToken())
                .header("Idempotency-Key", key())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{not json"));

        for (Map.Entry<String, RequestBuilder> failure : failures.entrySet()) {
            assertIsTheDeclaredProblem(failure.getKey(), mvc.perform(failure.getValue()).andReturn().getResponse(), declared, required);
        }

        // And a bug: nothing anticipated it, and it is still a problem with a code, not a stack trace.
        ledger.broken(true);
        assertIsTheDeclaredProblem(
                "INTERNAL_ERROR", mvc.perform(placeOrder("{\"lines\":[{\"sku\":\"tessera\",\"quantity\":1}]}")).andReturn().getResponse(),
                declared, required);
    }

    private void assertIsTheDeclaredProblem(
            String code, MockHttpServletResponse response, Map<String, Object> declared, List<?> required) throws Exception {
        String what = code + " (" + response.getStatus() + " " + response.getContentAsString() + ")";
        assertTrue(response.getContentType() != null && response.getContentType().startsWith("application/problem+json"), what);
        Map<String, Object> sent = parse(response.getContentAsString());
        assertEquals(code, sent.get("code"), what);
        assertEquals(response.getStatus(), sent.get("status"), what);
        // Everything sent is declared: a field the contract does not mention is how a client ends up
        // parsing by hand.
        for (String field : sent.keySet()) {
            assertTrue(declared.containsKey(field), what + " sends " + field + ", which the contract omits");
        }
        // Everything required is sent: the direction that catches an over-promise.
        for (Object field : required) {
            assertTrue(sent.containsKey(field), what + " lacks " + field + ", which the contract requires");
        }
    }

    private RequestBuilder placeOrder(String body) {
        return post("/api/orders")
                .with(customer("alice"))
                .with(csrfToken())
                .header("Idempotency-Key", key())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private String spec() throws Exception {
        MockHttpServletResponse response = mvc.perform(get("/v3/api-docs")).andReturn().getResponse();
        assertEquals(200, response.getStatus(), response.getContentAsString());
        return response.getContentAsString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parse(String document) {
        return json.readValue(document, Map.class);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Map<String, Object> parent, String name) {
        Object child = parent.get(name);
        assertTrue(child instanceof Map, name + " is not in the document");
        return (Map<String, Object>) child;
    }

    private String canonical(String document) {
        // Sorting an ObjectNode is not something Jackson will do; sorting a Map is.
        return json.rebuild()
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .enable(SerializationFeature.INDENT_OUTPUT)
                .build()
                .writeValueAsString(parse(document))
                .strip();
    }
}
