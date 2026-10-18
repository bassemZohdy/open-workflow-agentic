package io.github.bassemzohdy.openworkflow.spikec;

import io.cloudevents.CloudEvent;
import io.cloudevents.core.builder.CloudEventBuilder;
import io.serverlessworkflow.impl.WorkflowApplication;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Publishes CloudEvents into the Quarkus Flow engine (in-process delivery, WP-003 fact 6). */
@ApplicationScoped
public class Events {

    @Inject
    WorkflowApplication application;

    public void publish(String type, String jsonData, String correlationId) {
        publish(UUID.randomUUID().toString(), type, jsonData, correlationId);
    }

    /** Same event id on every call: a redelivered callback is recognisable as the same event. */
    public void publish(String eventId, String type, String jsonData, String correlationId) {
        var builder = CloudEventBuilder.v1()
                .withId(eventId)
                .withSource(URI.create("urn:spikec:agent"))
                .withType(type)
                .withDataContentType("application/json")
                .withData(jsonData.getBytes(StandardCharsets.UTF_8));
        if (correlationId != null) {
            builder = builder.withExtension("correlationid", correlationId);
        }
        CloudEvent event = builder.build();
        application.eventPublishers().forEach(p -> p.publish(event));
    }
}
