package io.github.bassemzohdy.openworkflow.agentic.contracts.governance;

import io.github.bassemzohdy.openworkflow.agentic.contracts.common.Checks;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.Subject;
import java.util.Objects;

/**
 * Input to a policy decision: who asks, to do what, to which resource, within which agent task, and with which facts
 * about the call.
 *
 * @param subject  the actor asking (usually the agent, with the user's grant as context)
 * @param action   the kind of action
 * @param resource the target, for example {@code tool:weather} or {@code scope:customer.profile.read}
 * @param taskId   the agent task, or null for a request made outside an agent task
 * @param context  facts a policy may read: scopes, risk tier, budget, the user acted for and the grant
 */
public record AuthorizationRequest(Subject subject, ActionKind action, String resource, Ulid taskId,
                                   PolicyContext context) {

    public static final int MAX_RESOURCE_LENGTH = 512;

    public AuthorizationRequest {
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(action, "action");
        resource = Checks.requireText("resource", resource, MAX_RESOURCE_LENGTH);
        Objects.requireNonNull(context, "context");
    }

    /** A request with no facts beyond the request itself. */
    public AuthorizationRequest(Subject subject, ActionKind action, String resource, Ulid taskId) {
        this(subject, action, resource, taskId, PolicyContext.EMPTY);
    }
}
