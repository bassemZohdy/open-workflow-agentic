package io.github.bassemzohdy.openworkflow.agentic.runtime.adapter.source;

import io.serverlessworkflow.impl.TaskContext;
import io.serverlessworkflow.impl.WorkflowContext;
import io.serverlessworkflow.impl.WorkflowModel;
import io.serverlessworkflow.impl.resources.URITemplateResolver;
import jakarta.ws.rs.core.UriBuilder;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

/** Compatibility adapter for SDK 7.35.3.Final's full-URI-as-path interpolation.
 * Registered by this module's URITemplateResolver service descriptor, ahead of the
 * pinned SDK resolver (priority 1000). Changes ship only after normal integration review.
 * This retains the pinned JAX-RS template subset; it is not a target authorization policy
 * or a general RFC 6570 implementation. */
public final class ComponentUriTemplateResolver implements URITemplateResolver {
    @Override
    public int priority() {
        return 100;
    }

    @Override
    public URI resolveTemplates(String template, WorkflowContext workflow, TaskContext task, WorkflowModel input) {
        return UriBuilder.fromUri(template)
                // RESTEasy probes containsKey(null); Map.of() rejects even that read.
                .resolveTemplates(new LinkedHashMap<>(input.asMap().orElse(Map.of())))
                .build();
    }
}
