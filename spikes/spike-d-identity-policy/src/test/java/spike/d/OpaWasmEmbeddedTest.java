package spike.d;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.openpolicyagent.wasm.OpaPolicy;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OPA embedded as Wasm (org.openpolicyagent:opa-java-wasm 2.0.0 on the Endive pure-Java runtime).
 * The module tool_authz.wasm was built from tool_authz.rego with opa 1.13.2:
 *   opa build -t wasm -e tool/allow tool_authz.rego -o bundle.tar.gz && tar -xzf bundle.tar.gz /policy.wasm
 */
class OpaWasmEmbeddedTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static byte[] module() throws IOException {
        try (InputStream in = OpaWasmEmbeddedTest.class.getResourceAsStream("/opa/tool_authz.wasm")) {
            return in.readAllBytes();
        }
    }

    /** PEP: allow only for a boolean true result; anything else, including an evaluation error, denies. */
    static boolean allowed(OpaPolicy policy, Map<String, Object> input) {
        try {
            JsonNode result = JSON.readTree(policy.evaluate(JSON.writeValueAsString(input))).path(0).path("result");
            return result.isBoolean() && result.asBoolean();
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    static OpaPolicy load() throws IOException {
        return OpaPolicy.builder().withPolicy(module()).build().entrypoint("tool/allow");
    }

    static Map<String, Object> input(String tool, String requiredScope, List<String> scopes, int budget,
                                     String risk, boolean approved) {
        return OpaSidecarTest.input(tool, requiredScope, scopes, budget, risk, approved);
    }

    @Test
    void decisionsMatchTheRulesOfTheCedarPolicy() throws Exception {
        OpaPolicy p = load();
        assertTrue(allowed(p, input("search", "cases:read", List.of("cases:read"), 5, "low", false)));
        assertFalse(allowed(p, input("search", "cases:read", List.of("cases:read"), 0, "low", false)));
        assertFalse(allowed(p, input("close_case", "cases:write", List.of("cases:read"), 5, "high", true)));
        assertFalse(allowed(p, input("close_case", "cases:write", List.of("cases:write"), 5, "high", false)));
        assertTrue(allowed(p, input("close_case", "cases:write", List.of("cases:write"), 5, "high", true)));
        assertFalse(allowed(p, Map.of("action", "call_tool", "budget_remaining", 5)));
    }

    @Test
    void malformedModuleIsRefusedAtLoad() {
        byte[] garbage = "not wasm".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertThrows(RuntimeException.class, () -> OpaPolicy.builder().withPolicy(garbage).build());
    }

    @Test
    void decisionLatencyTenThousandCalls() throws Exception {
        OpaPolicy p = load();
        Map<String, Object> allow = input("search", "cases:read", List.of("cases:read"), 5, "low", false);
        Map<String, Object> deny = input("close_case", "cases:write", List.of("cases:write"), 5, "high", false);
        for (int i = 0; i < 2_000; i++) {
            allowed(p, allow);
            allowed(p, deny);
        }
        long[] nanos = new long[10_000];
        for (int i = 0; i < nanos.length; i++) {
            boolean expected = i % 2 == 0;
            long t0 = System.nanoTime();
            boolean got = allowed(p, expected ? allow : deny);
            nanos[i] = System.nanoTime() - t0;
            assertEquals(expected, got);
        }
        Latency.report("opa-wasm-embedded-in-process", nanos);
    }
}
