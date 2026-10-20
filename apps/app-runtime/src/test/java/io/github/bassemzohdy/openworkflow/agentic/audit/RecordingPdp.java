package io.github.bassemzohdy.openworkflow.agentic.audit;

import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.AuthorizationRequest;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.Decision;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.DecisionOutcome;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.PolicyRef;
import io.github.bassemzohdy.openworkflow.agentic.contracts.port.PolicyDecisionPoint;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Singleton;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A policy decision point for the audit API tests. It records every request it is asked about and allows only the
 * subjects the test names. It is a CDI alternative: it is a bean only in test profiles that enable it
 * ({@code QuarkusTestProfile.getEnabledAlternatives}), so no other test in the application sees a bound policy.
 */
@Alternative
@Singleton
public class RecordingPdp implements PolicyDecisionPoint {

    private static final List<AuthorizationRequest> SEEN = new CopyOnWriteArrayList<>();
    private static final Set<String> ALLOWED = ConcurrentHashMap.newKeySet();

    /** Forgets earlier requests and allows exactly the subjects with these ids. */
    public static void reset(String... allowedSubjectIds) {
        SEEN.clear();
        ALLOWED.clear();
        ALLOWED.addAll(List.of(allowedSubjectIds));
    }

    /** The requests the policy was asked since the last {@link #reset}. */
    public static List<AuthorizationRequest> seen() {
        return List.copyOf(SEEN);
    }

    @Override
    public Decision decide(AuthorizationRequest request) {
        SEEN.add(request);
        DecisionOutcome outcome = ALLOWED.contains(request.subject().id()) ? DecisionOutcome.ALLOW : DecisionOutcome.DENY;
        return new Decision("recording-pdp", outcome, "test decision", PolicyRef.FAIL_CLOSED, Instant.now());
    }
}
