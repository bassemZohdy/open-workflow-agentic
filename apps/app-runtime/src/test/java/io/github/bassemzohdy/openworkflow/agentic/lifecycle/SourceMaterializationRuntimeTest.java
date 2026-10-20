package io.github.bassemzohdy.openworkflow.agentic.lifecycle;

import static org.junit.jupiter.api.Assertions.*;
import static io.github.bassemzohdy.openworkflow.agentic.runtime.adapter.source.WorkflowSourceCodec.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bassemzohdy.openworkflow.agentic.runtime.adapter.source.WorkflowSourceCodec;
import io.github.bassemzohdy.openworkflow.agentic.runtime.adapter.source.WorkflowSourceMaterializer;
import io.quarkus.test.junit.QuarkusTest;
import io.serverlessworkflow.impl.WorkflowApplication;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** Checked authoring adapter with actual injected Flow, resource loading and HTTP.
 * The raw SDK API deliberately remains separate; publication/admission must consume this port. */
@QuarkusTest
class SourceMaterializationRuntimeTest {
    @Inject WorkflowApplication application;
    @Inject ObjectMapper json;

    private String source(int port, String rootFormat, String lateFormat) {
        String late = lateFormat == null ? "" : "      input:\n        schema:\n          format: "
                + lateFormat + "\n          document: {type: object}\n";
        return """
                document: {dsl: '1.0.3', namespace: gpt-materialization, name: check-%s, version: '1.0.0'}
                input:
                  schema:
                    format: %s
                    resource: {endpoint: http://127.0.0.1:%d/schema.json}
                do:
                  - reach:
                      call: http
                      with:
                        method: get
                        endpoint:
                          uri: http://127.0.0.1:%d/effect
                %s
                """.formatted(UUID.randomUUID(), rootFormat, port, port, late);
    }

    @Test void wholeSourceCheckRefusesUnsupportedRootAndLateDeclarationsBeforeAnySchemaFetchOrExecution() throws Exception {
        var codec = new WorkflowSourceCodec(); var materializer = new WorkflowSourceMaterializer(codec);
        var report = json.createObjectNode().put("scope", "Checked source materializer with actual injected Flow; no approval or target authority");
        var results = report.putArray("cases");
        try (var fixture = new InputSchemaRuntimeTest.Fixture()) {
            for (String placement : List.of("root-external", "late-task", "declared-version")) {
                String root = placement.equals("root-external") ? "unregistered" : placement.equals("declared-version") ? "json:2020-12" : "json";
                byte[] source = source(fixture.port(), root, placement.equals("late-task") ? "unregistered" : null).getBytes(StandardCharsets.UTF_8);
                var imported = codec.importSource(source, Format.YAML, Profile.DEVELOPMENT_1_0_3);
                assertTrue(imported.schemaAccepted(), imported.diagnostics().toString());
                var refused = assertThrows(WorkflowSourceMaterializer.Refused.class,
                        () -> materializer.materialize(materializer.prepare(imported, ConversionPolicy.ALLOW_FORMATTING_CHANGES), application));
                assertTrue(refused.diagnostics().stream().allMatch(d -> d.code() == Code.UNSUPPORTED_SCHEMA_FORMAT));
                assertEquals(0, fixture.schemaReads.get()); assertEquals(0, fixture.effects.get()); assertTrue(fixture.requests.isEmpty());
                assertArrayEquals(source, imported.original());
                var item = results.addObject().put("placement", placement).put("schemaAccepted", true)
                        .put("status", "REFUSED_BEFORE_SDK_DEFINITION").put("sourceSha256", imported.originalSha256())
                        .put("schemaReads", fixture.schemaReads.get()).put("httpEffects", fixture.effects.get());
                item.set("diagnostics", json.valueToTree(refused.diagnostics()));
            }
        }
        write("refusals", report);
    }

    @Test void acceptedJsonSourceMaterializesThenFetchesItsSchemaAndExecutesOnActualFlow() throws Exception {
        var codec = new WorkflowSourceCodec(); var materializer = new WorkflowSourceMaterializer(codec);
        try (var fixture = new InputSchemaRuntimeTest.Fixture()) {
            byte[] source = source(fixture.port(), "json", null).getBytes(StandardCharsets.UTF_8);
            var imported = codec.importSource(source, Format.YAML, Profile.DEVELOPMENT_1_0_3);
            assertTrue(codec.runtimeCapabilityDiagnostics(imported).isEmpty());
            assertEquals(0, fixture.schemaReads.get()); assertEquals(0, fixture.effects.get());
            var prepared = materializer.prepare(imported, ConversionPolicy.ALLOW_FORMATTING_CHANGES);
            assertEquals(imported.originalSha256(), prepared.originalSha256());
            assertEquals(0, fixture.schemaReads.get()); assertEquals(0, fixture.effects.get());
            byte[] copy = prepared.executableJson(); copy[0] = 0;
            assertNotEquals(0, prepared.executableJson()[0]);
            var definition = materializer.materialize(prepared, application);
            assertEquals(1, fixture.schemaReads.get()); assertEquals(0, fixture.effects.get());
            var instance = definition.instance(Map.of("expected", "valid"));
            var output = instance.start().get(5, TimeUnit.SECONDS);
            assertEquals("COMPLETED", instance.status().name());
            assertEquals(Map.of("reached", true), output.asMap().orElseThrow());
            assertEquals(1, fixture.schemaReads.get()); assertEquals(1, fixture.effects.get());
            assertEquals(List.of("GET /schema.json", "GET /effect"), fixture.requests);
            assertArrayEquals(source, imported.original());
            var report = json.createObjectNode().put("scope", "Checked source materializer with actual injected Flow; no approval or target authority")
                    .put("sourceSha256", imported.originalSha256()).put("executableSha256", prepared.executableSha256())
                    .put("status", instance.status().name()).put("instanceId", instance.id())
                    .put("schemaReads", fixture.schemaReads.get()).put("httpEffects", fixture.effects.get());
            report.set("output", json.valueToTree(output.asJavaObject())); report.set("requests", json.valueToTree(fixture.requests));
            write("accepted", report);
        }
    }

    private void write(String name, com.fasterxml.jackson.databind.JsonNode report) throws Exception {
        Path path = Path.of("target/conformance/materializer/" + name + ".json"); Files.createDirectories(path.getParent());
        Files.writeString(path, json.writerWithDefaultPrettyPrinter().writeValueAsString(report) + "\n");
    }
}
