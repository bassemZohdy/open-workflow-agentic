package spike.d;

import java.util.Set;

/**
 * The port every enforcement point calls. The engine behind it (Cedar here, OPA in the spike's
 * sidecar test) is what WP-006 compares. Implementations must return deny when they cannot decide.
 */
public interface Pdp {

    Decision decide(Request request);

    /** A tool call by an agent that acts for a user, with the authority the user delegated. */
    record Request(String agentId, String userId, String tool, Set<String> scopes,
                   long budgetRemaining, boolean humanApproved) {
        public Request {
            scopes = Set.copyOf(scopes);
        }
    }

    record Decision(boolean allowed, String reason) {
        public static Decision deny(String reason) {
            return new Decision(false, reason);
        }
    }
}
