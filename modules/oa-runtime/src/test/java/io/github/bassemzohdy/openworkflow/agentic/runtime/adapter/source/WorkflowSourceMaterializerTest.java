package io.github.bassemzohdy.openworkflow.agentic.runtime.adapter.source;

import static org.junit.jupiter.api.Assertions.*;
import static io.github.bassemzohdy.openworkflow.agentic.runtime.adapter.source.WorkflowSourceCodec.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.serverlessworkflow.impl.WorkflowApplication;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class WorkflowSourceMaterializerTest {
    final WorkflowSourceCodec codec = new WorkflowSourceCodec();
    final WorkflowSourceMaterializer materializer = new WorkflowSourceMaterializer(codec);
    final ObjectMapper json = new ObjectMapper();
    WorkflowSourceMaterializerTest() throws Exception { }

    ObjectNode node(String source) throws Exception { return (ObjectNode) json.readTree(source); }
    ObjectNode base(Profile profile) throws Exception {
        return node("{\"document\":{\"dsl\":\"" + (profile == Profile.RELEASED_1_0_1 ? "1.0.1" : "1.0.3")
                + "\",\"namespace\":\"capability\",\"name\":\"check\",\"version\":\"1.0.0\"},\"do\":[{\"leaf\":{\"set\":{\"payload\":1}}}]}");
    }
    Imported read(ObjectNode source, Profile profile) throws Exception {
        var imported = codec.importSource(json.writeValueAsBytes(source), Format.JSON, profile);
        assertTrue(imported.schemaAccepted(), imported.diagnostics().toString());
        return imported;
    }
    ObjectNode badLeaf() throws Exception {
        return node("{\"set\":{\"payload\":1},\"input\":{\"schema\":{\"format\":\"unregistered\",\"document\":{\"type\":\"object\"}}}}");
    }

    @Test void schemaAwareOverlayChecksRootTaskCompositeReusableAndExtensionDeclarationsInBothProfiles() throws Exception {
        var evidence = json.createObjectNode().put("scope", "Offline whole-source capability check before SDK definition construction");
        var observations = evidence.putArray("cases");
        for (var profile : Profile.values()) {
            var cases = new LinkedHashMap<String, ObjectNode>();
            for (String slot : List.of("input", "output")) {
                var root = base(profile); root.set(slot, node("{\"schema\":{\"format\":\"unregistered\",\"resource\":{\"endpoint\":\"https://schema.invalid/schema.json\"}}}"));
                cases.put("root-" + slot, root);
            }
            for (String slot : List.of("input", "output", "export")) {
                var root = base(profile);
                ((ObjectNode)root.path("do").get(0).path("leaf")).set(slot, node("{\"schema\":{\"format\":\"unregistered\",\"document\":{}},\"" + (slot.equals("input") ? "from" : "as") + "\":\".\"}"));
                cases.put("task-" + slot, root);
            }
            for (String kind : List.of("do", "for", "fork", "try", "catch", "listen")) {
                var root = base(profile); ObjectNode composite;
                if (kind.equals("for")) composite = node("{\"for\":{\"each\":\"item\",\"in\":\"${ [1] }\"},\"do\":[]}");
                else if (kind.equals("fork")) composite = node("{\"fork\":{\"branches\":[]}}");
                else if (kind.equals("try") || kind.equals("catch")) composite = node("{\"try\":[{\"safe\":{\"set\":{\"value\":1}}}],\"catch\":{\"do\":[{\"safe\":{\"set\":{\"value\":1}}}]}}");
                else if (kind.equals("listen")) composite = node("{\"listen\":{\"to\":{\"one\":{\"with\":{\"type\":\"org.example.event\"}}}},\"foreach\":{\"do\":[]}}");
                else composite = node("{\"do\":[]}");
                var item = json.createObjectNode(); item.set("nested", badLeaf());
                if (kind.equals("fork")) ((com.fasterxml.jackson.databind.node.ArrayNode)composite.path("fork").path("branches")).add(item);
                else if (kind.equals("catch")) ((ObjectNode)composite.path("catch")).putArray("do").add(item);
                else if (kind.equals("listen")) ((ObjectNode)composite.path("foreach")).putArray("do").add(item);
                else composite.putArray(kind.equals("try") ? "try" : "do").add(item);
                root.putArray("do").add(json.createObjectNode().set("outer", composite));
                cases.put(kind, root);
            }
            var functions = base(profile); functions.set("use", json.createObjectNode().set("functions", json.createObjectNode().set("reusable", badLeaf())));
            cases.put("function", functions);
            for (String slot : List.of("before", "after")) {
                var root = base(profile); var extension = node("{\"extend\":\"all\"}");
                extension.putArray(slot).add(json.createObjectNode().set("nested", badLeaf()));
                var use = root.putObject("use"); use.putArray("extensions").add(json.createObjectNode().set("extension", extension));
                cases.put("extension-" + slot, root);
            }
            assertEquals(14, cases.size());
            var calls = new AtomicInteger();
            try (var app = WorkflowApplication.builder().withResourceLoaderFactory((application, path) -> {
                calls.incrementAndGet(); throw new AssertionError("Must not construct an SDK definition");
            }).build()) {
                for (var entry : cases.entrySet()) {
                    var imported = read(entry.getValue(), profile);
                    var refused = assertThrows(WorkflowSourceMaterializer.Refused.class,
                            () -> materializer.materialize(materializer.prepare(imported, ConversionPolicy.PRESERVE_ORIGINAL), app), entry.getKey());
                    assertFalse(refused.diagnostics().isEmpty());
                    assertTrue(refused.diagnostics().stream().allMatch(d -> d.code() == Code.UNSUPPORTED_SCHEMA_FORMAT));
                    assertEquals(0, calls.get());
                    assertArrayEquals(json.writeValueAsBytes(entry.getValue()), imported.original());
                    var item = observations.addObject().put("profile", profile.name()).put("location", entry.getKey())
                            .put("schemaAccepted", true).put("sourceSha256", imported.originalSha256()).put("sdkDefinitionAttempts", calls.get());
                    item.set("diagnostics", json.valueToTree(refused.diagnostics()));
                }
            }
        }
        assertEquals(28, observations.size());
        java.nio.file.Path path = java.nio.file.Path.of("target/conformance/materializer/structural-refusals.json");
        java.nio.file.Files.createDirectories(path.getParent());
        java.nio.file.Files.writeString(path, json.writerWithDefaultPrettyPrinter().writeValueAsString(evidence) + "\n");
    }

    @Test void exactJsonAndOmittedDefaultPassButVersionsWhitespaceCaseAndSecretLikeFormatsRefuseWithoutEcho() throws Exception {
        for (var profile : Profile.values()) {
            for (String format : List.of("json", "OMITTED", "json:2020-12", "JSON", " json", "", "https://user:private@schema.invalid/format")) {
                var root = base(profile); var schema = root.putObject("input").putObject("schema");
                schema.putObject("document").put("type", "object");
                if (!format.equals("OMITTED")) schema.put("format", format);
                var imported = read(root, profile); var diagnostics = codec.runtimeCapabilityDiagnostics(imported);
                assertEquals(format.equals("json") || format.equals("OMITTED"), diagnostics.isEmpty());
                assertFalse(diagnostics.toString().contains("private"));
            }
        }
    }

    @Test void opaquePayloadSchemaKeysAreDataAndActualSdkExecutionRemainsAvailable() throws Exception {
        var root = base(Profile.DEVELOPMENT_1_0_3);
        ((ObjectNode)root.path("do").get(0).path("leaf")).set("set", node("{\"schema\":{\"format\":\"xml\"},\"payload\":{\"input\":{\"schema\":{\"format\":\"secret\"}}}}"));
        var imported = read(root, Profile.DEVELOPMENT_1_0_3);
        assertTrue(codec.runtimeCapabilityDiagnostics(imported).isEmpty());
        try (var app = WorkflowApplication.builder().disableLifeCycleCEPublishing().disableStatusChangePublishing().build()) {
            var definition = materializer.materialize(materializer.prepare(imported, ConversionPolicy.PRESERVE_ORIGINAL), app);
            var output = definition.instance(Map.of()).start().get(2, TimeUnit.SECONDS).asMap().orElseThrow();
            assertEquals(Map.of("format", "xml"), output.get("schema"));
        }
    }

    @Test void sourceAndConversionRefusalsPrecedeSdkBindingAndOriginalBytesRemainAvailable() throws Exception {
        byte[] bytes = "document: broken\n".getBytes(StandardCharsets.UTF_8);
        var imported = codec.importSource(bytes, Format.YAML, Profile.DEVELOPMENT_1_0_3);
        try (var app = WorkflowApplication.builder().build()) {
            assertThrows(WorkflowSourceMaterializer.Refused.class, () -> materializer.materialize(materializer.prepare(imported, ConversionPolicy.ALLOW_FORMATTING_CHANGES), app));
        }
        assertArrayEquals(bytes, imported.original());
        try (var stream = getClass().getResourceAsStream("/conformance/cases/root-unrecognized-field.yaml"); var app = WorkflowApplication.builder().build()) {
            assertNotNull(stream);
            var unknown = codec.importSource(stream.readAllBytes(), Format.YAML, Profile.DEVELOPMENT_1_0_3);
            assertTrue(unknown.schemaAccepted());
            var refused = assertThrows(WorkflowSourceMaterializer.Refused.class,
                    () -> materializer.materialize(materializer.prepare(unknown, ConversionPolicy.ALLOW_FORMATTING_CHANGES), app));
            assertEquals(Code.SDK_BINDING_UNSUPPORTED, refused.diagnostics().getFirst().code());
        }
        byte[] yaml = "document: {dsl: '1.0.3', namespace: capability, name: acknowledgement, version: '1.0.0'}\ndo: [{copy: {set: {value: 1}}}]\n".getBytes(StandardCharsets.UTF_8);
        var validYaml = codec.importSource(yaml, Format.YAML, Profile.DEVELOPMENT_1_0_3);
        assertTrue(validYaml.schemaAccepted());
        try (var app = WorkflowApplication.builder().build()) {
            assertThrows(IllegalArgumentException.class,
                    () -> materializer.materialize(materializer.prepare(validYaml, ConversionPolicy.PRESERVE_ORIGINAL), app));
        }
        assertArrayEquals(yaml, validYaml.original());
    }

    @Test void acknowledgedYamlAliasMaterializesAsJsonBeforeActualSdkBinding() throws Exception {
        byte[] source = """
                document: {dsl: '1.0.3', namespace: capability, name: aliases, version: '1.0.0'}
                do:
                  - remember:
                      set:
                        first: &fixture {label: example}
                        second: *fixture
                """.getBytes(StandardCharsets.UTF_8);
        var imported = codec.importSource(source, Format.YAML, Profile.DEVELOPMENT_1_0_3);
        assertTrue(imported.schemaAccepted());
        try (var app = WorkflowApplication.builder().disableLifeCycleCEPublishing().disableStatusChangePublishing().build()) {
            var definition = materializer.materialize(materializer.prepare(imported, ConversionPolicy.ALLOW_FORMATTING_CHANGES), app);
            var output = definition.instance(Map.of()).start().get(2, TimeUnit.SECONDS).asMap().orElseThrow();
            assertEquals(Map.of("first", Map.of("label", "example"), "second", Map.of("label", "example")), output);
            assertArrayEquals(source, imported.original());
            var evidence = json.createObjectNode().put("scope", "Checked materializer and actual SDK execution; no activation authority")
                    .put("sourceSha256", imported.originalSha256());
            evidence.set("output", json.valueToTree(output));
            java.nio.file.Path path = java.nio.file.Path.of("target/conformance/materializer/alias-materialization.json");
            java.nio.file.Files.createDirectories(path.getParent());
            java.nio.file.Files.writeString(path, json.writerWithDefaultPrettyPrinter().writeValueAsString(evidence) + "\n");
        }
    }

    @Test void preservedJsonBomAndPreparedByteCopiesKeepTheCheckedViewImmutable() throws Exception {
        byte[] jsonBytes = json.writeValueAsBytes(base(Profile.DEVELOPMENT_1_0_3));
        byte[] bytes = new byte[jsonBytes.length + 3]; bytes[0] = (byte)0xef; bytes[1] = (byte)0xbb; bytes[2] = (byte)0xbf;
        System.arraycopy(jsonBytes, 0, bytes, 3, jsonBytes.length);
        var imported = codec.importSource(bytes, Format.JSON, Profile.DEVELOPMENT_1_0_3);
        assertTrue(imported.schemaAccepted());
        var prepared = materializer.prepare(imported, ConversionPolicy.PRESERVE_ORIGINAL);
        assertArrayEquals(bytes, prepared.executableJson());
        assertEquals(imported.originalSha256(), prepared.originalSha256());
        assertEquals(prepared.originalSha256(), prepared.executableSha256());
        byte[] changed = prepared.executableJson(); changed[0] = 0;
        assertArrayEquals(bytes, prepared.executableJson());
        try (var app = WorkflowApplication.builder().disableLifeCycleCEPublishing().disableStatusChangePublishing().build()) {
            var result = materializer.materialize(prepared, app).instance(Map.of()).start().get(2, TimeUnit.SECONDS);
            assertEquals(Map.of("payload", 1), result.asMap().orElseThrow());
        }
    }
}
