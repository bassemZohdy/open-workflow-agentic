package io.github.bassemzohdy.openworkflow.agentic.runtime.conformance;

import static org.junit.jupiter.api.Assertions.*;

import io.github.bassemzohdy.openworkflow.agentic.runtime.adapter.source.ComponentUriTemplateResolver;
import io.serverlessworkflow.impl.WorkflowApplication;
import io.serverlessworkflow.impl.resources.URITemplateResolver;
import java.net.URI;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UriTemplateAdapterTest {
    @TempDir Path services;
    final ComponentUriTemplateResolver resolver = new ComponentUriTemplateResolver();

    @Test void queryAndFragmentRemainSeparateComponentsAndPathSubstitutionsCannotAddSegments() {
        try (var app = WorkflowApplication.builder().build()) {
            URI actual = resolver.resolveTemplates("https://example.invalid/pets/{id}?status={status}#details", null, null,
                    app.modelFactory().from(Map.of("id", "a/b?c#d", "status", "ready&admin=true")));
            assertEquals("https", actual.getScheme());
            assertEquals("example.invalid", actual.getRawAuthority());
            assertEquals("/pets/a%2Fb%3Fc%23d", actual.getRawPath());
            assertEquals("status=ready%26admin%3Dtrue", actual.getRawQuery());
            assertEquals("details", actual.getRawFragment());
        }
    }

    @Test void literalEscapesAndRepeatedVariablesRetainTheirComponentMeaning() {
        try (var app = WorkflowApplication.builder().build()) {
            URI actual = resolver.resolveTemplates("https://example.invalid/a%2Fb/{id}?literal=x%26y&id={id}", null, null,
                    app.modelFactory().from(Map.of("id", "x y")));
            assertEquals("/a%2Fb/x%20y", actual.getRawPath());
            assertEquals("literal=x%26y&id=x+y", actual.getRawQuery());
        }
    }

    @Test void unresolvedVariablesRefuseForMissingAndNonObjectInput() {
        try (var app = WorkflowApplication.builder().build()) {
            assertThrows(IllegalArgumentException.class, () -> resolver.resolveTemplates(
                    "https://example.invalid/{missing}", null, null, app.modelFactory().from(Map.of())));
            assertThrows(IllegalArgumentException.class, () -> resolver.resolveTemplates(
                    "https://example.invalid/?q={missing}", null, null, app.modelFactory().from("scalar")));
            assertEquals(URI.create("https://example.invalid/a?q=literal#fragment"), resolver.resolveTemplates(
                    "https://example.invalid/a?q=literal#fragment", null, null, app.modelFactory().fromNull()));
        }
    }

    @Test void unchangedOfficialHttpQueryCasePassesThroughActualSdkWithAnExplicitIsolatedServiceProvider() throws Exception {
        String feature = OwsConformance.resource("upstream/ctk/features/call.feature");
        var scenario = OfficialCtkTest.parse("call.feature", feature).stream()
                .filter(s -> s.steps().stream().anyMatch(step -> step.block() != null
                        && step.block().contains("findByStatus?status={status}")))
                .findFirst().orElseThrow();
        String source = scenario.steps().stream().filter(s -> s.text().equals("Given a workflow with definition:"))
                .findFirst().orElseThrow().block();
        var input = OfficialCtkTest.yaml(scenario.steps().stream().filter(s -> s.text().equals("And given the workflow input is:"))
                .findFirst().orElseThrow().block());
        CtkProviders.requireControlledSource(OfficialCtkTest.yaml(source));

        Path descriptor = services.resolve("META-INF/services/" + URITemplateResolver.class.getName());
        Files.createDirectories(descriptor.getParent());
        Files.writeString(descriptor, ComponentUriTemplateResolver.class.getName() + "\n");
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        try (var loader = new URLClassLoader(new java.net.URL[]{services.toUri().toURL()}, previous);
             var providers = new CtkProviders()) {
            Thread.currentThread().setContextClassLoader(loader);
            try {
                try (var selected = WorkflowApplication.builder().build()) {
                    assertInstanceOf(ComponentUriTemplateResolver.class, selected.templateResolver().orElseThrow());
                }
                var actual = OfficialCtkTest.execute(source, input, providers.configure(WorkflowApplication.builder()), providers);
                int checked = 0;
                for (var step : scenario.steps()) {
                    if (step.text().startsWith("Then ") || (step.text().startsWith("And ")
                            && !step.text().equals("And given the workflow input is:"))) {
                        assertTrue(OfficialCtkTest.assertion(step, actual), step.text());
                        checked++;
                    }
                }
                assertTrue(checked > 0);
                assertEquals(1, providers.responses.get());
                assertEquals(1, providers.requests.size());
                assertEquals("https://petstore.swagger.io/v2/pet/findByStatus?status=available",
                        providers.requests.getFirst().path("originalUri").asText());
                assertTrue(providers.refusals.isEmpty());
                Path artifact = Path.of("target/conformance/uri-adapter/observation.json");
                Files.createDirectories(artifact.getParent());
                var record = OwsConformance.JSON.createObjectNode().put("scenario", scenario.name())
                        .put("definitionSha256", OwsConformance.sha(source)).put("assertionsChecked", checked)
                        .put("activation", "ISOLATED_TEST_SERVICE_PROVIDER_ONLY").put("status", actual.status());
                record.set("output", actual.output());
                record.set("provider", providers.observation());
                Files.writeString(artifact, OwsConformance.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(record) + "\n");
            } finally {
                Thread.currentThread().setContextClassLoader(previous);
            }
        }
        assertSame(previous, Thread.currentThread().getContextClassLoader());
        try (var nativeApp = WorkflowApplication.builder().build()) {
            assertEquals("io.serverlessworkflow.impl.template.JaxrsURITemplateResolver",
                    nativeApp.templateResolver().orElseThrow().getClass().getName());
        }
    }
}
