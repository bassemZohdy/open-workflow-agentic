package spike.d;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Decision latency for Cedar in process: 2,000 warm-up calls, then 10,000 measured calls. */
class CedarLatencyTest {

    private static final int WARMUP = 2_000;
    private static final int CALLS = 10_000;

    private CedarPdp pdp;

    @BeforeEach
    void load() throws IOException {
        pdp = new CedarPdp();
        try (InputStream in = CedarLatencyTest.class.getResourceAsStream("/policies/tool-calls.cedar")) {
            pdp.load(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @Test
    void cedarDecisionLatencyTenThousandCalls() {
        Pdp.Request allow = CedarToolAuthzTest.request("search", Set.of("cases:read"), 5, false);
        Pdp.Request deny = CedarToolAuthzTest.request("close_case", Set.of("cases:write"), 5, false);
        for (int i = 0; i < WARMUP; i++) {
            pdp.decide(allow);
            pdp.decide(deny);
        }
        long[] nanos = new long[CALLS];
        for (int i = 0; i < CALLS; i++) {
            Pdp.Request r = (i % 2 == 0) ? allow : deny;
            long t0 = System.nanoTime();
            Pdp.Decision d = pdp.decide(r);
            nanos[i] = System.nanoTime() - t0;
            assertEquals(i % 2 == 0, d.allowed(), d.reason());
        }
        Latency.report("cedar-in-process", nanos);
    }
}
