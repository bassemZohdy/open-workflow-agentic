package spike.d;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Fail closed: the shipped policy file cannot be parsed, so the PDP holds nothing and denies every call. */
@QuarkusTest
@TestProfile(ToolCallBrokenPolicyTest.BrokenPolicy.class)
class ToolCallBrokenPolicyTest {

    public static class BrokenPolicy implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("spike.policy.resource", "/policies/broken.cedar");
        }
    }

    @TestHTTPResource("/tools")
    URL tools;

    @Test
    @TestSecurity(user = "agent-42", roles = {"cases:read"})
    void everyCallIsDeniedWhenThePolicySetIsBroken() throws Exception {
        assertEquals(403, ToolCallHttpPolicyTest.call(tools, "search"));
        assertEquals(403, ToolCallHttpPolicyTest.call(tools, "close_case"));
    }
}
