package io.github.bassemzohdy.openworkflow.agentic.lifecycle;

import static org.junit.jupiter.api.Assertions.*;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.*;
import java.util.Map;
import org.junit.jupiter.api.Test;

@QuarkusTest
@QuarkusTestResource(value=ManualEffectsApiResource.class, restrictToAnnotatedClass=true)
@TestProfile(ManualEffectsPolicyUnavailableApiTest.Profile.class)
class ManualEffectsPolicyUnavailableApiTest extends ManualEffectsApiFixtures {
    public static final class Profile implements QuarkusTestProfile {
        @Override public Map<String,String> getConfigOverrides() {
            return Map.of("agentic.operations.policy-folder","policy/missing-manual-api-test");
        }
    }

    @Test void unavailablePolicyCannotRetainOrApplyAManualDecision() throws Exception {
        var f=unresolved(tenant(),"unavailable-policy");String request=io.github.bassemzohdy.openworkflow.agentic.contracts.id.UlidGenerator.shared().next().value();
        var response=resolve(f,operator(f.tenant()),request,"CONFIRMED","TARGET_CONFIRMED");
        assertEquals(503,response.statusCode(),response.asString());
        assertEquals(0,count("select count(*) from effect.manual_decision where request_id='"+request+"'"));
    }
}
