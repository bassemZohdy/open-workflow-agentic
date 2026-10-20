package io.github.bassemzohdy.openworkflow.agentic.lifecycle;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.bassemzohdy.openworkflow.agentic.runtime.adapter.source.ComponentUriTemplateResolver;
import io.quarkus.test.junit.QuarkusTest;
import io.serverlessworkflow.api.WorkflowFormat;
import io.serverlessworkflow.api.WorkflowReader;
import io.serverlessworkflow.impl.WorkflowApplication;
import jakarta.inject.Inject;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** Actual injected Flow application and its HTTP client, targeting an owned loopback fixture.
 * This verifies URI integration, not admission/target authorization or business-effect audit. */
@QuarkusTest
class UriTemplateRuntimeTest {
    @Inject WorkflowApplication application;
    @Inject ObjectMapper json;

    @Test void injectedFlowApplicationUsesThePackagedResolverAndPreservesQueryAndEscapedPathOnActualHttpRequests() throws Exception {
        assertInstanceOf(ComponentUriTemplateResolver.class, application.templateResolver().orElseThrow());
        var requests = new CopyOnWriteArrayList<URI>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try {
                requests.add(exchange.getRequestURI());
                byte[] body = "{\"uriAdapterReached\":true}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            String source = """
                    document:
                      dsl: '1.0.3'
                      namespace: gpt-uri-integration
                      name: query-%s
                      version: '1.0.0'
                    do:
                      - fetch:
                          call: http
                          with:
                            method: get
                            endpoint:
                              uri: http://127.0.0.1:%d/pets/{id}?status={status}
                    """.formatted(UUID.randomUUID(), port);
            var definition = application.workflowDefinition(WorkflowReader.readWorkflowFromString(source, WorkflowFormat.YAML));
            var observations = json.createObjectNode().put("scope", "Actual injected Quarkus Flow JVM application; owned loopback HTTP only")
                    .put("resolver", application.templateResolver().orElseThrow().getClass().getName())
                    .put("port", port);
            var cases = observations.putArray("cases");
            var inputs = java.util.List.of(Map.<String, Object>of("id", "1", "status", "available"),
                    Map.<String, Object>of("id", "a/b?c#d", "status", "ready&admin=true"));
            for (var input : inputs) {
                var instance = definition.instance(input);
                var output = instance.start().get(5, TimeUnit.SECONDS);
                assertEquals(Boolean.TRUE, output.asMap().orElseThrow().get("uriAdapterReached"));
                assertEquals("COMPLETED", instance.status().name());
                var item = cases.addObject().put("instanceId", instance.id().toString()).put("status", instance.status().name());
                item.set("input", json.valueToTree(input));
                item.set("output", json.valueToTree(output.asJavaObject()));
            }
            assertEquals(2, requests.size());
            assertEquals("/pets/1?status=available", requests.get(0).toString());
            assertEquals("/pets/a%2Fb%3Fc%23d?status=ready%26admin%3Dtrue", requests.get(1).toString());
            for (int index = 0; index < requests.size(); index++) {
                var uri = requests.get(index);
                assertNull(uri.getRawAuthority());
                assertNull(uri.getRawFragment());
                ((com.fasterxml.jackson.databind.node.ObjectNode) cases.get(index)).put("actualRequestTarget", uri.toString());
            }
            Path artifact = Path.of("target/conformance/uri-runtime/observation.json");
            Files.createDirectories(artifact.getParent());
            Files.writeString(artifact, json.writerWithDefaultPrettyPrinter().writeValueAsString(observations) + "\n");
        } finally {
            server.stop(0);
        }
    }
}
