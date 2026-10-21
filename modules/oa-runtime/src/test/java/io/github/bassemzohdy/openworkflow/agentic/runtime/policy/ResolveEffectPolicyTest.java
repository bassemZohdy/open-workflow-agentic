package io.github.bassemzohdy.openworkflow.agentic.runtime.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.ActionKind;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.AuthorizationRequest;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.DecisionOutcome;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.PolicyContext;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.Subject;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.SubjectKind;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * WP-108: the bundled operations snapshot decides {@code operation:resolve-effect} with the real Cedar engine. The manual
 * decision on an unknown governed effect needs {@code oa.recover}; the scope that covers retry and recover
 * ({@code oa.operate}), inspect or nothing does not, and neither does a service principal.
 */
class ResolveEffectPolicyTest {

    private static final Ulid INSTANCE = Ulid.parse("01ARZ3NDEKTSV4RRFFQ69G5FAV");

    private static CedarPolicyDecisionPoint bundled() throws Exception {
        var pdp = new CedarPolicyDecisionPoint();
        pdp.install(ManifestPolicySnapshotSource.ofClasspath(ResolveEffectPolicyTest.class.getClassLoader(),
                "policy/operations").read());
        return pdp;
    }

    private static AuthorizationRequest resolve(Subject who, Set<String> scopes) {
        return new AuthorizationRequest(who, ActionKind.OPERATION, Pep.InstanceOperation.RESOLVE_EFFECT.resource(),
                INSTANCE, new PolicyContext(scopes, null, null, null, null, null));
    }

    @Test
    void onlyAUserWithTheRecoverScopeMayResolveAnEffect() throws Exception {
        var pdp = bundled();
        Subject user = new Subject(SubjectKind.USER, "ops-lead");
        assertEquals(DecisionOutcome.ALLOW, pdp.decide(resolve(user, Set.of("oa.recover"))).outcome());
        for (Set<String> scopes : java.util.List.of(Set.<String>of(), Set.of("oa.operate"), Set.of("oa.inspect"),
                Set.of("oa.operate", "oa.inspect", "oa.agent.run"))) {
            assertEquals(DecisionOutcome.DENY, pdp.decide(resolve(user, scopes)).outcome(), scopes.toString());
        }
        assertEquals(DecisionOutcome.DENY,
                pdp.decide(resolve(new Subject(SubjectKind.SERVICE, "svc"), Set.of("oa.recover"))).outcome());
    }

    @Test
    void theRecoverScopeStillDoesNotOpenRetryOrRecover() throws Exception {
        var pdp = bundled();
        Subject user = new Subject(SubjectKind.USER, "ops-lead");
        for (Pep.InstanceOperation op : new Pep.InstanceOperation[] {Pep.InstanceOperation.RETRY,
                Pep.InstanceOperation.RECOVER}) {
            var request = new AuthorizationRequest(user, ActionKind.OPERATION, op.resource(), INSTANCE,
                    new PolicyContext(Set.of("oa.recover"), null, null, null, null, null));
            assertEquals(DecisionOutcome.DENY, pdp.decide(request).outcome(), op.name());
        }
    }
}
