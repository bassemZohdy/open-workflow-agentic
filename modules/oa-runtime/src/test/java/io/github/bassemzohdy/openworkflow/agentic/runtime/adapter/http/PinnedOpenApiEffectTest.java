package io.github.bassemzohdy.openworkflow.agentic.runtime.adapter.http;

import static org.junit.jupiter.api.Assertions.*;

import io.github.bassemzohdy.openworkflow.agentic.contracts.common.CanonicalEncoder;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid;
import io.github.bassemzohdy.openworkflow.agentic.runtime.agent.GovernedToolGateway;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PinnedOpenApiEffectTest {
    private static final URI ORIGIN = URI.create("https://orders.example");
    private static final String DOC = """
            {"openapi":"3.1.0","info":{"title":"Orders","version":"1"},
             "servers":[{"url":"https://untrusted.example"}],
             "paths":{"/orders":{"post":{"operationId":"createOrder",
              "requestBody":{"required":true,"content":{"application/json":{"schema":
                {"type":"object","required":["amount"],"properties":{"amount":{"type":"integer"}}}}}},
              "responses":{"200":{"description":"complete"},"202":{"description":"accepted"}}}}}}
            """;
    private static final PinnedHttpEffectTarget.CredentialExpectation EXPECTED =
            new PinnedHttpEffectTarget.CredentialExpectation("issuer", Ulid.parse("01ARZ3NDEKTSV4RRFFQ69G5FAV"), Set.of("orders:write"));

    private static PinnedOpenApiEffect operation(String doc, String id, URI origin) {
        return new PinnedOpenApiEffect(doc, CanonicalEncoder.sha256Hex(CanonicalEncoder.strictUtf8(doc)), id, origin);
    }
    private static GovernedToolGateway.Registration registration(PinnedOpenApiEffect operation, Set<Integer> statuses) {
        return operation.registration("tool:orders", "orders", "corr", Duration.ofSeconds(2), statuses, EXPECTED,
                (audience, action) -> { throw new AssertionError("registration must not acquire credentials"); }, token -> { });
    }
    private static String fingerprint(GovernedToolGateway.Registration registration) {
        return GovernedToolGateway.configurationFingerprint(Map.of("tool:orders", registration));
    }

    @Test void pinsDocumentAndTrustedOriginIntoGatewayConfiguration() {
        var first = registration(operation(DOC, "createOrder", ORIGIN), Set.of(200));
        assertEquals(fingerprint(first), fingerprint(registration(operation(DOC, "createOrder", ORIGIN), Set.of(200))));
        assertNotEquals(fingerprint(first), fingerprint(registration(operation(DOC, "createOrder", URI.create("https://other.example")), Set.of(200))));
        assertNotEquals(fingerprint(first), fingerprint(registration(operation(DOC + " ", "createOrder", ORIGIN), Set.of(200))));
        assertThrows(IllegalArgumentException.class, () -> new PinnedOpenApiEffect(DOC, "0".repeat(64), "createOrder", ORIGIN));
    }

    @Test void rejectsUnsupportedSelectionAndAmbiguousJson() {
        assertThrows(IllegalArgumentException.class, () -> operation(DOC, "missing", ORIGIN));
        assertThrows(IllegalArgumentException.class, () -> operation(DOC.replace("/orders", "/orders/{id}"), "createOrder", ORIGIN));
        assertThrows(IllegalArgumentException.class, () -> operation(DOC.replace("\"post\"", "\"get\""), "createOrder", ORIGIN));
        assertThrows(IllegalArgumentException.class, () -> operation(DOC.replace("\"paths\":{\"/orders\":", "\"paths\":{\"/other\":{\"get\":{\"operationId\":\"createOrder\"}},\"/orders\":"), "createOrder", ORIGIN));
        assertThrows(IllegalArgumentException.class, () -> operation(DOC.replace("\"openapi\":\"3.1.0\"", "\"openapi\":\"3.1.0\",\"openapi\":\"3.1.0\""), "createOrder", ORIGIN));
        assertThrows(IllegalArgumentException.class, () -> operation(DOC, "createOrder", URI.create("http://orders.example")));
        assertThrows(IllegalArgumentException.class, () -> operation(DOC, "createOrder", URI.create("https://orders.example/api")));
    }

    @Test void refusesReferencesParametersAndUndocumentedConfirmation() {
        assertThrows(IllegalArgumentException.class, () -> operation(DOC.replace("\"type\":\"object\"", "\"type\":\"object\",\"$ref\":\"https://evil/schema\""), "createOrder", ORIGIN));
        assertThrows(IllegalArgumentException.class, () -> operation(DOC.replace("\"openapi\":\"3.1.0\"", "\"openapi\":\"3.1.0\",\"jsonSchemaDialect\":\"https://evil/dialect\""), "createOrder", ORIGIN));
        assertThrows(IllegalArgumentException.class, () -> operation(DOC.replace("\"operationId\":\"createOrder\"", "\"operationId\":\"createOrder\",\"parameters\":[]"), "createOrder", ORIGIN));
        assertThrows(IllegalArgumentException.class, () -> operation(DOC.replace("\"operationId\":\"createOrder\"", "\"operationId\":\"createOrder\",\"servers\":[]"), "createOrder", ORIGIN));
        assertThrows(IllegalArgumentException.class, () -> operation(DOC.replace("\"requestBody\":{", "\"requestBody\":{\"$ref\":\"#/components/requestBodies/order\","), "createOrder", ORIGIN));
        var op = operation(DOC, "createOrder", ORIGIN);
        assertThrows(IllegalArgumentException.class, () -> registration(op, Set.of(201)));
        assertThrows(IllegalArgumentException.class, () -> registration(op, Set.of(202)));
    }
}
