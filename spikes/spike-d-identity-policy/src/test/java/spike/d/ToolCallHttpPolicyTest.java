package spike.d;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Quarkus HTTP security policy (HttpSecurityPolicy named "tool-call") in front of the tool endpoint.
 * Identities come from @TestSecurity; their roles named with ':' are the delegated scopes.
 */
@QuarkusTest
class ToolCallHttpPolicyTest {

    @TestHTTPResource("/tools")
    URL tools;

    static final HttpClient HTTP = HttpClient.newHttpClient();

    static int call(URL base, String tool) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/" + tool + "/call"))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        return HTTP.send(req, HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    @Test
    void anonymousCallerIsRejected() throws Exception {
        int status = call(tools, "search");
        assertTrue(status == 401 || status == 403, "anonymous must not reach the tool, got " + status);
        System.out.println("QUARKUS_ANONYMOUS status=" + status);
    }

    @Test
    @TestSecurity(user = "agent-42", roles = {"cases:read"})
    void readToolPermittedWithItsScope() throws Exception {
        assertEquals(200, call(tools, "search"));
    }

    @Test
    @TestSecurity(user = "agent-42", roles = {"cases:read"})
    void writeToolDeniedWithoutItsScope() throws Exception {
        assertEquals(403, call(tools, "close_case"));
    }

    @Test
    @TestSecurity(user = "agent-42", roles = {"cases:write"})
    void highRiskToolDeniedWithoutHumanApproval() throws Exception {
        // The scope is right; the forbid policy still denies because no approval is recorded.
        assertEquals(403, call(tools, "close_case"));
    }

    @Test
    @TestSecurity(user = "agent-42", roles = {"cases:read", "cases:write"})
    void unknownToolIsDeniedEvenWithAllScopes() throws Exception {
        assertEquals(403, call(tools, "drop_database"));
    }

    @Test
    @TestSecurity(user = "agent-42", roles = {"cases:read"})
    void pathVariantsDoNotBypassThePolicy() throws Exception {
        // A high-risk call the caller is not allowed to make, sent with path variants.
        // Any 200 here would be a bypass. Other statuses are recorded for the findings.
        String[] variants = {
            "close_case/call/", "%63lose_case/call", "close_case//call", "./close_case/call", "CLOSE_CASE/call"
        };
        for (String v : variants) {
            int status = HTTP.send(HttpRequest.newBuilder(URI.create(tools + "/" + v))
                    .POST(HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.ofString()).statusCode();
            System.out.println("QUARKUS_PATH_VARIANT " + v + " status=" + status);
            assertTrue(status != 200, "bypass via path variant " + v);
        }
    }
}
