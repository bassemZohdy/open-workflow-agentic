package io.github.bassemzohdy.openworkflow.agentic.runtime.api;

import io.github.bassemzohdy.openworkflow.agentic.runtime.agent.ResearchAgent;
import io.github.bassemzohdy.openworkflow.agentic.runtime.agent.ResearchState;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.Map;
import java.util.UUID;

/** Agent step endpoint. Workflows call this over HTTP (OWS "call: http"); it can also be used directly. */
@Path("/agents/research")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class AgentResource {

    public record Request(String topic, String threadId) {}

    @Inject
    ResearchAgent agent;

    @POST
    public Map<String, Object> run(Request request) {
        var threadId = request.threadId() == null ? UUID.randomUUID().toString() : request.threadId();
        var state = agent.run(threadId, request.topic());
        return Map.of(
                "threadId", threadId,
                "topic", request.topic(),
                "plan", state.text(ResearchState.PLAN).orElse(""),
                "draft", state.text(ResearchState.DRAFT).orElse(""),
                "review", state.text(ResearchState.REVIEW).orElse(""));
    }
}
