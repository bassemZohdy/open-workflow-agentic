package io.github.bassemzohdy.openworkflow.agentic.runtime.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.Decision;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.DecisionOutcome;
import io.github.bassemzohdy.openworkflow.agentic.contracts.common.ThreadId;
import io.github.bassemzohdy.openworkflow.agentic.runtime.agent.ResearchAgent;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.AgentResourceAdmission;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.TokenRejectedException;
import io.github.bassemzohdy.openworkflow.agentic.runtime.agent.ResearchState;
import io.github.bassemzohdy.openworkflow.agentic.runtime.api.caller.CallerContext;
import io.github.bassemzohdy.openworkflow.agentic.runtime.api.caller.CallerContextResolver;
import io.github.bassemzohdy.openworkflow.agentic.runtime.api.problem.ProblemException;
import io.github.bassemzohdy.openworkflow.agentic.runtime.policy.Pep;
import jakarta.inject.Inject;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
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
 *   <li>The caller is established: a delegated token (a workflow step acting for a user) through
 *       {@link AgentResourceAdmission} (exact audience, scope {@code oa.agent.run}, grant, binding, single use), anything
 *       else by {@link CallerContextResolver}: 401 without a usable credential, 503 when the token cannot be checked. The body is not read before this, so an anonymous caller learns nothing from body errors.</li>
 *   <li>The policy enforcement point decides {@link Pep#AGENT_RESEARCH_RUN} for the caller's subject and scopes
 *       ({@code oa.agent.run}). DENY is 403, an unavailable decision is 503; the decision is audited.</li>
 *   <li>The body is parsed: 400 when it is not a JSON object with a non-blank {@code topic}.</li>
 *   <li>The agent runs on {@link ThreadId#derive} of (tenant, subject kind and id, visible thread id): the thread is
 *       owned by one user of one tenant, so neither another tenant nor another user of the same tenant reaches it by
 *       sending the same visible id, and the owner reaches the same thread again. The caller's id is returned unchanged.
 *       A direct run has no registry instance, so there is no {@code agent_task} row; the ownership is the derivation.</li>
 * </ol>
 */
@Path("/agents/research")
@Produces(MediaType.APPLICATION_JSON)
public class AgentResource {

    public record Request(String topic, String threadId) {}

    private static final Pattern THREAD_ID = Pattern.compile("[A-Za-z0-9._:-]{1,128}");
    private static final Pattern BEARER = Pattern.compile("(?i)^bearer +([^ ,]+)$");
    private static final int MAX_TOPIC = 4096;

    @Inject
    ResearchAgent agent;

    @Inject
    CallerContextResolver callers;

    @Inject
    Pep pep;

    @Inject
    ObjectMapper json;

    @Inject
    AgentResourceAdmission admission;

    @POST
    public Map<String, Object> run(@Context HttpHeaders headers, String body) {
        CallerContext caller = caller(headers);
        authorize(caller);
        Request request = parse(body);
        var threadId = request.threadId() == null ? UUID.randomUUID().toString() : request.threadId();
        // the thread is owned by (tenant, full subject kind and id): another user of the same tenant who sends the same
        // visible id derives a different thread, the same user always the same one (ADR 0020, decision 1)
        var thread = ThreadId.derive(caller.tenant(), caller.subject().kind().name() + ":" + caller.subject().id(),
                "direct:" + threadId);
        var state = agent.run(thread.value(), request.topic());
        return Map.of(
                "threadId", threadId,
                "topic", request.topic(),
                "plan", state.text(ResearchState.PLAN).orElse(""),
                "draft", state.text(ResearchState.DRAFT).orElse(""),
                "review", state.text(ResearchState.REVIEW).orElse(""));
    }

    /**
     * A user's own token (or, in development only, the trusted headers) goes through the caller resolver. A delegated
     * token, which the resolver refuses, is admitted through {@link AgentResourceAdmission}: the governed path of a
     * workflow step acting for a user. The result is the user the call is for, with the delegated scopes.
     */
    private CallerContext caller(HttpHeaders headers) {
        String bearer = bearer(headers);
        if (bearer != null && admission.configured()) {
            try {
                var principal = admission.admit(bearer);
                return new CallerContext(principal.tenant(), principal.user(), principal.scopes(),
                        CallerContext.Provenance.VERIFIED_TOKEN);
            } catch (TokenRejectedException refused) {
                switch (refused.reason()) {
                    // not a delegated token for this resource: the resolver decides (user token, or 401)
                    case AUDIENCE, WRONG_TOKEN_KIND, MALFORMED, SIGNATURE, ISSUER, MISSING -> { }
                    case VERIFIER_UNAVAILABLE -> throw new ProblemException(Response.Status.SERVICE_UNAVAILABLE,
                            "the bearer token could not be verified; try again later");
                    case SCOPE -> throw new ProblemException(Response.Status.FORBIDDEN,
                            "the token does not carry the scope this resource needs");
                    default -> throw new WebApplicationException("the bearer token is not valid", Response
                            .status(Response.Status.UNAUTHORIZED)
                            .header(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"invalid_token\"").build());
                }
            }
        }
        return callers.resolve(headers);
    }

    private static String bearer(HttpHeaders headers) {
        var values = headers.getRequestHeader(HttpHeaders.AUTHORIZATION);
        if (values == null || values.size() != 1) {
            return null;
        }
        var matcher = BEARER.matcher(values.get(0).trim());
        return matcher.matches() ? matcher.group(1) : null;
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
