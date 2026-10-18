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

/** Fail closed: the policy engine throws while deciding. An otherwise permitted call must be refused. */
@QuarkusTest
@TestProfile(ToolCallFailClosedTest.EngineOutage.class)
class ToolCallFailClosedTest {

    public static class EngineOutage implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("spike.pdp.unavailable", "true");
        }
    }

    @TestHTTPResource("/tools")
    URL tools;

    @Test
    @TestSecurity(user = "agent-42", roles = {"cases:read"})
    void permittedCallIsDeniedWhenEngineThrows() throws Exception {
        assertEquals(403, ToolCallHttpPolicyTest.call(tools, "search"));
    }
}
