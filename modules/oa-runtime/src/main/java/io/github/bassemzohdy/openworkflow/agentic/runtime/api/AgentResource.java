package io.github.bassemzohdy.openworkflow.agentic.runtime.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.Decision;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.DecisionOutcome;
import io.github.bassemzohdy.openworkflow.agentic.runtime.agent.ResearchAgent;
import io.github.bassemzohdy.openworkflow.agentic.runtime.agent.ResearchState;
import io.github.bassemzohdy.openworkflow.agentic.runtime.api.caller.CallerContext;
import io.github.bassemzohdy.openworkflow.agentic.runtime.api.caller.CallerContextResolver;
import io.github.bassemzohdy.openworkflow.agentic.runtime.api.problem.ProblemException;
import io.github.bassemzohdy.openworkflow.agentic.runtime.policy.Pep;
import jakarta.inject.Inject;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Agent step endpoint (WP-103). Workflows call this over HTTP (OWS "call: http"); it can also be used directly.
 *
 * <p>The order is fixed and nothing runs before the last check has passed:
 * <ol>
 *   <li>The caller is established by {@link CallerContextResolver}: 401 without a usable credential, 503 when the token
 *       cannot be checked. The body is not read before this, so an anonymous caller learns nothing from body errors.</li>
 *   <li>The policy enforcement point decides {@link Pep#AGENT_RESEARCH_RUN} for the caller's subject and scopes
 *       ({@code oa.agent.run}). DENY is 403, an unavailable decision is 503; the decision is audited.</li>
 *   <li>The body is parsed: 400 when it is not a JSON object with a non-blank {@code topic}.</li>
 *   <li>The agent runs on a thread id that is private to the caller's tenant, so one tenant cannot read or continue
 *       another tenant's checkpoints by guessing a thread id. The caller's own id is returned unchanged.</li>
 * </ol>
 */
@Path("/agents/research")
@Produces(MediaType.APPLICATION_JSON)
public class AgentResource {

    public record Request(String topic, String threadId) {}

    private static final Pattern THREAD_ID = Pattern.compile("[A-Za-z0-9._:-]{1,128}");
    private static final int MAX_TOPIC = 4096;

    @Inject
    ResearchAgent agent;

    @Inject
    CallerContextResolver callers;

    @Inject
    Pep pep;

    @Inject
    ObjectMapper json;

    @POST
    public Map<String, Object> run(@Context HttpHeaders headers, String body) {
        CallerContext caller = callers.resolve(headers);
        authorize(caller);
        Request request = parse(body);
        var threadId = request.threadId() == null ? UUID.randomUUID().toString() : request.threadId();
        var state = agent.run(caller.tenant() + "/" + threadId, request.topic());
        return Map.of(
                "threadId", threadId,
                "topic", request.topic(),
                "plan", state.text(ResearchState.PLAN).orElse(""),
                "draft", state.text(ResearchState.DRAFT).orElse(""),
                "review", state.text(ResearchState.REVIEW).orElse(""));
    }

    private void authorize(CallerContext caller) {
        if (caller == null || caller.provenance() == CallerContext.Provenance.UNVERIFIED || caller.subject() == null) {
            throw new ProblemException(Response.Status.FORBIDDEN, "the caller is not authorized to run this agent");
        }
        Decision decision;
        try {
            decision = pep.agentInvocation(caller.subject(), caller.scopes());
        } catch (RuntimeException unavailable) {
            throw new ProblemException(Response.Status.SERVICE_UNAVAILABLE,
                    "the policy decision is unavailable; the agent was not run");
        }
        if (decision == null || decision.outcome() == DecisionOutcome.ERROR) {
            throw new ProblemException(Response.Status.SERVICE_UNAVAILABLE,
                    "the policy decision is unavailable; the agent was not run");
        }
        if (!decision.allows()) {
            throw new ProblemException(Response.Status.FORBIDDEN, "the caller is not authorized to run this agent");
        }
    }

    private Request parse(String body) {
        Request request;
        try {
            request = body == null || body.isBlank() ? null : json.readValue(body, Request.class);
        } catch (Exception malformed) {
            throw new ProblemException(Response.Status.BAD_REQUEST, "the request body is not valid");
        }
        if (request == null || request.topic() == null || request.topic().isBlank()
                || request.topic().length() > MAX_TOPIC
                || (request.threadId() != null && !THREAD_ID.matcher(request.threadId()).matches())) {
            throw new ProblemException(Response.Status.BAD_REQUEST,
                    "topic is required (at most " + MAX_TOPIC + " characters) and threadId must match "
                            + THREAD_ID.pattern());
        }
        return request;
    }
}
