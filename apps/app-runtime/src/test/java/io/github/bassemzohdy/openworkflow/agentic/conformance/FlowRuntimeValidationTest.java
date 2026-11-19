package io.github.bassemzohdy.openworkflow.agentic.conformance;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import io.github.bassemzohdy.openworkflow.agentic.runtime.adapter.source.ComponentUriTemplateResolver;
import io.github.bassemzohdy.openworkflow.agentic.runtime.adapter.source.WorkflowSourceCodec;
import io.github.bassemzohdy.openworkflow.agentic.runtime.adapter.source.WorkflowSourceMaterializer;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.serverlessworkflow.api.WorkflowFormat;
import io.serverlessworkflow.api.WorkflowReader;
import io.serverlessworkflow.impl.WorkflowApplication;
import io.serverlessworkflow.impl.WorkflowDefinition;
import io.serverlessworkflow.impl.WorkflowException;
import io.serverlessworkflow.impl.jackson.schema.JsonSchemaValidatorFactory;
import io.serverlessworkflow.types.Errors;
import jakarta.inject.Inject;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;

/** Injected default Flow application, real SDK providers and owned HTTP; no production authority or activation. */
@QuarkusTest
@TestProfile(FlowRuntimeValidationTest.IsolatedProfile.class)
@Timeout(30)
class FlowRuntimeValidationTest {
    // Own the injected application's definition cache without changing any settings or provider registrations.
    public static class IsolatedProfile implements QuarkusTestProfile { }
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<Map<String, Object>> OBSERVATIONS = new CopyOnWriteArrayList<>();
    @Inject WorkflowApplication application;
    HttpServer server;
    ObjectNode fixture;
    final AtomicInteger schemaReads = new AtomicInteger();
    final AtomicInteger httpCalls = new AtomicInteger();
    final AtomicReference<Throwable> serverFailure = new AtomicReference<>();

    @BeforeEach void startOwnedServer() throws Exception {
        assertInstanceOf(ComponentUriTemplateResolver.class, application.templateResolver().orElseThrow());
        assertInstanceOf(JsonSchemaValidatorFactory.class, application.validatorFactory());
        try (var source = getClass().getResourceAsStream("/conformance/flow-runtime-probe.yaml")) {
            assertNotNull(source);
            fixture = (ObjectNode) WorkflowFormat.YAML.mapper().readTree(source);
        }
        byte[] schema = JSON.writeValueAsBytes(fixture.path("input").path("schema").path("document"));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/schema/", exchange -> {
            try {
                assertTrue(exchange.getRequestURI().getRawPath().matches("/schema/probe-[0-9a-f]{32}\\.json"));
                assertEquals("GET", exchange.getRequestMethod());
                schemaReads.incrementAndGet();
                exchange.getResponseHeaders().set("Content-Type", "application/schema+json");
                exchange.sendResponseHeaders(200, schema.length);
                exchange.getResponseBody().write(schema);
            } catch (Throwable failure) { serverFailure.compareAndSet(null, failure); }
            finally { exchange.close(); }
        });
        server.createContext("/observe/", exchange -> {
            try {
                assertEquals("GET", exchange.getRequestMethod());
                httpCalls.incrementAndGet();
                var uri = exchange.getRequestURI();
                byte[] body = JSON.writeValueAsBytes(Map.of("path", uri.getRawPath(),
                        "query", Objects.toString(uri.getRawQuery(), "")));
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } catch (Throwable failure) { serverFailure.compareAndSet(null, failure); }
            finally { exchange.close(); }
        });
        server.start();
    }

    @AfterEach void stopOwnedServer() {
        if (server != null) server.stop(0);
        assertNull(serverFailure.get(), "the owned HTTP provider must not hide a handler failure");
    }

    private ObjectNode source(String format, boolean external) {
        var source = fixture.deepCopy();
        ((ObjectNode) source.path("document")).put("name", "probe-" + UUID.randomUUID().toString().replace("-", ""));
        var schema = (ObjectNode) source.path("input").path("schema");
        if (format == null) schema.remove("format"); else schema.put("format", format);
        if (external) {
            schema.remove("document");
            // Every source gets a cold resource URL; a previous raw binding must not hide an accidental fetch.
            schema.putObject("resource").put("endpoint", origin() + "/schema/" + source.path("document").path("name").asText() + ".json");
        }
        ((ObjectNode) source.path("do").get(0).path("observeInput").path("with"))
                .put("endpoint", origin() + "/observe/{id}?tag={tag}&literal=x%26y");
        return source;
    }

    private String origin() { return "http://127.0.0.1:" + server.getAddress().getPort(); }

    private WorkflowDefinition checked(ObjectNode source) throws Exception {
        var codec = new WorkflowSourceCodec();
        var imported = codec.importSource(JSON.writeValueAsBytes(source), WorkflowSourceCodec.Format.JSON,
                WorkflowSourceCodec.Profile.DEVELOPMENT_1_0_3);
        assertTrue(imported.schemaAccepted(), imported.diagnostics().toString());
        var materializer = new WorkflowSourceMaterializer(codec);
        return materializer.materialize(materializer.prepare(imported,
                WorkflowSourceCodec.ConversionPolicy.PRESERVE_ORIGINAL), application);
    }

    private Map<String, Object> run(WorkflowDefinition definition, String id, String tag) throws Exception {
        return definition.instance(Map.of("payload", Map.of("id", id, "tag", tag)))
                .start().get(10, TimeUnit.SECONDS).asMap().orElseThrow();
    }

    private void requireRawInputRefusal(WorkflowDefinition definition) {
        for (Map<String, Object> invalid : List.<Map<String, Object>>of(Map.of(),
                Map.of("payload", Map.of("id", 42, "tag", "ready")))) {
            int before = httpCalls.get();
            var failure = assertThrows(WorkflowException.class, () -> definition.instance(invalid));
            assertEquals(Errors.VALIDATION.toString(), failure.getWorkflowError().type());
            assertEquals(400, failure.getWorkflowError().status());
            assertEquals(before, httpCalls.get(), "invalid raw input must not reach the HTTP task");
        }
    }

    private void observe(Map<String, Object> values) {
        var record = new LinkedHashMap<>(values);
        record.put("validatorClass", application.validatorFactory().getClass().getName());
        record.put("uriResolverClass", application.templateResolver().orElseThrow().getClass().getName());
        OBSERVATIONS.add(Map.copyOf(record));
    }

    @Test void injectedUriProviderPreservesNormalAndEscapedComponentsThroughActualHttp() throws Exception {
        var definition = checked(source("json", false));
        assertEquals(Map.of("path", "/observe/plain", "query", "tag=ready&literal=x%26y"), run(definition, "plain", "ready"));
        var escaped = run(definition, "a/b?c#d", "ready&admin=true");
        assertEquals(Map.of("path", "/observe/a%2Fb%3Fc%23d", "query", "tag=ready%26admin%3Dtrue&literal=x%26y"), escaped);
        assertEquals(2, httpCalls.get());
        observe(Map.of("case", "injected-uri-components", "httpCalls", 2, "escapedOutput", escaped));
    }

    @Test void explicitAndOmittedJsonValidateRawInputBeforeUnwrappingAndHttp() throws Exception {
        for (boolean external : List.of(false, true)) {
            for (String format : List.of("json", "OMITTED")) {
                int reads = schemaReads.get(), calls = httpCalls.get();
                var definition = checked(source(format.equals("OMITTED") ? null : format, external));
                assertEquals(reads + (external ? 1 : 0), schemaReads.get());
                requireRawInputRefusal(definition);
                // The raw schema requires payload; the HTTP task instead receives its unwrapped id/tag object.
                assertEquals(Map.of("path", "/observe/42", "query", "tag=ready&literal=x%26y"), run(definition, "42", "ready"));
                assertEquals(calls + 1, httpCalls.get());
                observe(Map.of("case", "input-schema", "format", format, "external", external,
                        "invalidRawInputsRefused", 2, "httpCalls", 1, "schemaReads", schemaReads.get() - reads));
            }
        }
    }

    @Test void rawUnknownFormatFallsBackToJsonButCheckedBoundaryRefusesBeforeResourceAccess() throws Exception {
        for (boolean external : List.of(false, true)) {
            int reads = schemaReads.get(), calls = httpCalls.get();
            var raw = source("unregistered-fixture-format", external);
            var definition = application.workflowDefinition(WorkflowReader.readWorkflow(JSON.writeValueAsBytes(raw), WorkflowFormat.JSON));
            requireRawInputRefusal(definition);
            assertEquals(Map.of("path", "/observe/42", "query", "tag=ready&literal=x%26y"), run(definition, "42", "ready"));
            assertEquals(calls + 1, httpCalls.get());
            assertEquals(reads + (external ? 1 : 0), schemaReads.get(), "raw external binding reads the schema despite its unknown format");

            int beforeCheck = schemaReads.get(), definitions = application.workflowDefinitions().size();
            var codec = new WorkflowSourceCodec();
            var imported = codec.importSource(JSON.writeValueAsBytes(source("unregistered-fixture-format", external)),
                    WorkflowSourceCodec.Format.JSON, WorkflowSourceCodec.Profile.DEVELOPMENT_1_0_3);
            assertTrue(imported.schemaAccepted());
            var materializer = new WorkflowSourceMaterializer(codec);
            var failure = assertThrows(WorkflowSourceMaterializer.Refused.class, () -> materializer.materialize(
                    materializer.prepare(imported, WorkflowSourceCodec.ConversionPolicy.PRESERVE_ORIGINAL), application));
            assertFalse(failure.diagnostics().isEmpty());
            assertTrue(failure.diagnostics().stream().allMatch(d -> d.code() == WorkflowSourceCodec.Code.UNSUPPORTED_SCHEMA_FORMAT));
            assertEquals(beforeCheck, schemaReads.get());
            assertEquals(definitions, application.workflowDefinitions().size());
            assertEquals(calls + 1, httpCalls.get());
            observe(Map.of("case", "unknown-format", "external", external,
                    "rawSdkBehavior", "DIVERGED_JSON_FALLBACK", "checkedBoundary", "REFUSED_BEFORE_SDK_BINDING",
                    "invalidRawInputsRefused", 2, "rawSchemaReads", schemaReads.get() - reads, "rawHttpCalls", httpCalls.get() - calls,
                    "checkedSchemaReads", 0, "checkedHttpCalls", 0));
        }
    }

    @AfterAll static void retainObservations() throws Exception {
        assertEquals(7, OBSERVATIONS.size());
        Path output = Path.of("target/conformance/flow-runtime/observations.json");
        Files.createDirectories(output.getParent());
        Files.writeString(output, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                "scope", "Injected Quarkus Flow with actual SDK validator, URI resolver and owned loopback HTTP",
                "validationErrorType", Errors.VALIDATION.toString(),
                "notProved", List.of("public TLS", "production source/target authority", "native-image discovery", "activation"),
                "cases", OBSERVATIONS)) + "\n");
    }
}
