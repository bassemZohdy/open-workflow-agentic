package io.github.bassemzohdy.openworkflow.agentic.runtime.conformance;

import static org.junit.jupiter.api.Assertions.*;
import io.cloudevents.core.builder.CloudEventBuilder;
import io.serverlessworkflow.api.*;
import io.serverlessworkflow.impl.WorkflowApplication;
import java.net.URI;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class CtkProvidersTest {
    @Test void controlledSourceGuardRejectsUnregisteredCallsCommandsListenersAndRemoteSchemasWithoutReadingOpaquePayloadKeys() throws Exception {
        assertThrows(IllegalArgumentException.class,()->CtkProviders.requireControlledSource(OwsConformance.JSON.readTree("{\"do\":[{\"task\":{\"call\":\"unregistered\"}}]}")));
        assertThrows(IllegalArgumentException.class,()->CtkProviders.requireControlledSource(OwsConformance.JSON.readTree("{\"do\":[{\"task\":{\"try\":[{\"bad\":{\"run\":{}}}]}}]}")));
        assertThrows(IllegalArgumentException.class,()->CtkProviders.requireControlledSource(OwsConformance.JSON.readTree("{\"do\":[{\"task\":{\"listen\":{}}}]}")));
        assertThrows(IllegalArgumentException.class,()->CtkProviders.requireControlledSource(OwsConformance.JSON.readTree("{\"input\":{\"schema\":{}},\"do\":[]}")));
        CtkProviders.requireControlledSource(OwsConformance.JSON.readTree("{\"do\":[{\"copy\":{\"set\":{\"run\":{},\"listen\":{},\"call\":\"opaque-data\"}}}]}"));
    }
    @Test void actualHttpTransportReachesOnlyTheOwnedLoopbackFixtureAndUnknownTargetsRefuseBeforeTransport() throws Exception {
        try(var provider=new CtkProviders()) {
            try(var response=provider.client.target("https://petstore.swagger.io/v2/pet/1").request().get()) {
                assertEquals(200,response.getStatus());assertEquals(1,response.readEntity(com.fasterxml.jackson.databind.JsonNode.class).path("id").asInt());
            }
            assertEquals(1,provider.responses.get());assertEquals(1,provider.requests.size());
            assertEquals("https://petstore.swagger.io/v2/pet/1",provider.requests.getFirst().path("originalUri").asText());
            assertTrue(provider.requests.getFirst().path("transportUri").asText().startsWith("http://127.0.0.1:"));
            assertThrows(jakarta.ws.rs.ProcessingException.class,()->provider.client.target("https://example.invalid/").request().get());
            assertEquals(1,provider.responses.get());assertEquals(1,provider.requests.size());assertEquals(java.util.List.of("HTTP_ROUTE_REFUSED"),provider.refusals);
        }
    }
    @Test void actualResourceLoaderReturnsOnlyPinnedBytesAndHasNoFallbackForOtherUris() throws Exception {
        try(var provider=new CtkProviders();var app=provider.configure(WorkflowApplication.builder()).disableLifeCycleCEPublishing().disableStatusChangePublishing().build()) {
            provider.verifyBindings(app);
            var definition=app.workflowDefinition(WorkflowReader.readWorkflowFromString("""
                    document: {dsl: '1.0.3', namespace: ctk, name: provider-control, version: '1.0.0'}
                    do: [{copy: {set: {value: 1}}}]
                    """,WorkflowFormat.YAML));
            byte[] actual=definition.resourceLoader().loadURI(URI.create(CtkProviders.DOCUMENT),h->{
                try(var in=h.open()){return in.readAllBytes();}catch(java.io.IOException failed){throw new java.io.UncheckedIOException(failed);}
            });
            assertArrayEquals(provider.document,actual);assertEquals(1,provider.documentOpens.get());
            assertThrows(IllegalArgumentException.class,()->definition.resourceLoader().loadURI(URI.create("https://example.invalid/schema"),h->"unreachable"));
            assertEquals(1,provider.documentOpens.get());assertEquals(0,provider.responses.get());
            assertEquals(java.util.List.of("RESOURCE_ROUTE_REFUSED"),provider.refusals);
        }
    }
    @Test void incorrectGlobalSupplierBindingIsRefusedBeforeAnySdkWorkflowCanExecute() throws Exception {
        try(var provider=new CtkProviders();var app=WorkflowApplication.builder()
                .withAdditionalObject(io.serverlessworkflow.impl.executors.http.HttpClientResolver.HTTP_CLIENT_PROVIDER,()->provider.client).build()) {
            assertThrows(IllegalStateException.class,()->provider.verifyBindings(app));
            assertEquals(0,provider.responses.get());assertEquals(0,provider.requests.size());
        }
    }
    @Test void actualBrokerDeliversTheKnownCloudEventAndRefusesAnUnregisteredType() throws Exception {
        try(var provider=new CtkProviders()) {
            var event=CloudEventBuilder.v1().withId("ctk-positive-control").withSource(URI.create(CtkProviders.EVENT_SOURCE)).withType(CtkProviders.EVENT_TYPE)
                    .withData("application/json","{\"greetings\":\"fixture\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8)).build();
            provider.publishGuarded(event).get(2,TimeUnit.SECONDS);assertEquals(1,provider.deliveries.size());
            assertEquals("fixture",provider.deliveries.getFirst().path("data").path("greetings").asText());
            var refused=CloudEventBuilder.from(event).withType("urn:ctk:unregistered").build();
            assertThrows(ExecutionException.class,()->provider.publishGuarded(refused).get(2,TimeUnit.SECONDS));
            assertEquals(1,provider.deliveries.size());assertEquals(java.util.List.of("EVENT_ROUTE_REFUSED"),provider.refusals);
        }
    }
}
