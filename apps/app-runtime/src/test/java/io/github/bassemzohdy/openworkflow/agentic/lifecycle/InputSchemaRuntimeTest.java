package io.github.bassemzohdy.openworkflow.agentic.lifecycle;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.quarkus.test.junit.QuarkusTest;
import io.serverlessworkflow.api.WorkflowFormat;
import io.serverlessworkflow.api.WorkflowReader;
import io.serverlessworkflow.impl.WorkflowApplication;
import io.serverlessworkflow.impl.WorkflowException;
import jakarta.inject.Inject;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Actual injected Flow validation and HTTP, not an application admission/authority test.
 * Unsupported-format fallback is measured explicitly as DIVERGED, never certified support. */
@QuarkusTest
class InputSchemaRuntimeTest {
    @Inject WorkflowApplication application;
    @Inject ObjectMapper json;

    @Test void inlineJsonRejectsRawMissingAndWrongTypeBeforeFilteringOrAnyHttpTask() throws Exception {
        exercise("json", false);
    }

    @Test void externalJsonRejectsRawMissingAndWrongTypeBeforeFilteringOrAnyHttpTask() throws Exception {
        exercise("json", true);
    }

    @Test void omittedInlineFormatUsesJsonValidationWithoutAnEnablingConfigurationKey() throws Exception {
        exercise(null, false);
    }

    @Test void omittedExternalFormatUsesJsonValidationWithoutAnEnablingConfigurationKey() throws Exception {
        exercise(null, true);
    }

    @Test void unregisteredInlineFormatIsCurrentlyAcceptedAndInterpretedAsJsonNotSupportedFormatInteroperability() throws Exception {
        exercise("not-a-registered-format", false);
    }

    @Test void unregisteredExternalFormatCurrentlyFetchesTheSchemaAndInterpretsJsonWithoutCheckingTheDeclaredFormat() throws Exception {
        exercise("not-a-registered-format", true);
    }

    private void exercise(String format, boolean external) throws Exception {
        assertEquals("io.serverlessworkflow.impl.jackson.schema.JsonSchemaValidatorFactory",
                application.validatorFactory().getClass().getName());
        boolean supported = format == null || format.equals("json");
        try (var fixture = new Fixture()) {
            String schema = external
                    ? "resource: {endpoint: http://127.0.0.1:" + fixture.port() + "/schema.json}"
                    : "document: {type: object, required: [expected], properties: {expected: {type: string}}}";
            String source = """
                    document: {dsl: '1.0.3', namespace: gpt-input-schema, name: input-%s, version: '1.0.0'}
                    input:
                      schema:
                        %s
                        %s
                      from: '{expected: "filtered"}'
                    do:
                      - reach:
                          call: http
                          with:
                            method: get
                            endpoint:
                              uri: http://127.0.0.1:%d/effect
                          output:
                            as: $input
                    """.formatted(UUID.randomUUID(), format == null ? "" : "format: " + format, schema, fixture.port());
            var definition = application.workflowDefinition(WorkflowReader.readWorkflowFromString(source, WorkflowFormat.YAML));
            assertEquals(external ? 1 : 0, fixture.schemaReads.get());
            var report = json.createObjectNode().put("scope", "Actual injected Quarkus Flow JVM; workflow raw-input validation and owned HTTP only")
                    .put("declaredFormat", format).put("external", external).put("definitionCreated", true)
                    .put("validator", application.validatorFactory().getClass().getName())
                    .put("formatVerdict", supported ? "MATCH" : "DIVERGED: unregistered format accepted as JSON")
                    .put("port", fixture.port()).put("schemaReadsAtDefinition", fixture.schemaReads.get());
            var attempts = report.putArray("attempts");
            for (var input : List.of(Map.<String, Object>of(), Map.<String, Object>of("expected", 5))) {
                var refused = assertThrows(WorkflowException.class, () -> definition.instance(input));
                assertEquals(400, refused.getWorkflowError().status());
                assertEquals("https://serverlessworkflow.io/spec/1.0.0/errors/validation", refused.getWorkflowError().type());
                assertEquals(0, fixture.effects.get());
                assertTrue(definition.activeInstances().isEmpty());
                var item = attempts.addObject().put("stage", "INSTANCE_CREATION").put("status", "REFUSED")
                        .put("errorStatus", refused.getWorkflowError().status()).put("errorType", refused.getWorkflowError().type())
                        .put("errorDetail", refused.getWorkflowError().detail()).put("httpEffects", fixture.effects.get());
                item.set("input", json.valueToTree(input));
            }
            var instance = definition.instance(Map.of("expected", "raw-valid"));
            var output = instance.start().get(5, TimeUnit.SECONDS);
            assertEquals("COMPLETED", instance.status().name());
            assertEquals(Map.of("expected", "filtered"), output.asMap().orElseThrow());
            assertEquals(1, fixture.effects.get());
            assertEquals(external ? 1 : 0, fixture.schemaReads.get());
            assertEquals(external ? List.of("GET /schema.json", "GET /effect") : List.of("GET /effect"), fixture.requests);
            var success = attempts.addObject().put("stage", "EXECUTION").put("status", "COMPLETED")
                    .put("instanceId", instance.id()).put("httpEffects", fixture.effects.get());
            success.set("input", json.valueToTree(Map.of("expected", "raw-valid")));
            success.set("output", json.valueToTree(output.asJavaObject()));
            report.put("schemaReads", fixture.schemaReads.get());
            report.set("requests", json.valueToTree(fixture.requests));
            Path file = Path.of("target/conformance/input-schema/" + (external ? "external-" : "inline-")
                    + (format == null ? "default" : supported ? "json" : "unregistered") + ".json");
            Files.createDirectories(file.getParent());
            Files.writeString(file, json.writerWithDefaultPrettyPrinter().writeValueAsString(report) + "\n");
        }
    }

    static final class Fixture implements AutoCloseable {
        final AtomicInteger schemaReads = new AtomicInteger();
        final AtomicInteger effects = new AtomicInteger();
        final List<String> requests = new CopyOnWriteArrayList<>();
        final HttpServer server;

        Fixture() throws java.io.IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                try {
                    requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
                    String path = exchange.getRequestURI().toString();
                    String body;
                    int status = 200;
                    if (path.equals("/schema.json")) {
                        schemaReads.incrementAndGet();
                        body = "{\"type\":\"object\",\"required\":[\"expected\"],\"properties\":{\"expected\":{\"type\":\"string\"}}}";
                    } else if (path.equals("/effect")) {
                        effects.incrementAndGet();
                        body = "{\"reached\":true}";
                    } else {
                        status = 404;
                        body = "{}";
                    }
                    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(status, bytes.length);
                    exchange.getResponseBody().write(bytes);
                } finally {
                    exchange.close();
                }
            });
            server.start();
        }

        int port() { return server.getAddress().getPort(); }
        @Override public void close() { server.stop(0); }
    }
}
