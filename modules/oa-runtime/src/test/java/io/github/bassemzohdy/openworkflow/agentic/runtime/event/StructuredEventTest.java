package io.github.bassemzohdy.openworkflow.agentic.runtime.event;

import static org.junit.jupiter.api.Assertions.*;
import io.github.bassemzohdy.openworkflow.agentic.runtime.api.problem.ProblemException;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class StructuredEventTest {
    static final String PREFIX = "\"specversion\":\"1.0\",\"source\":\"urn:test:orders\",\"id\":\"one\",\"type\":\"order.ready\"";
    static StructuredEvent parse(String json) { return StructuredEvent.read(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8))); }

    @Test
    void canonicalizesNestedObjectsAndPreservesAllEventMetadataAndDataKinds() {
        var a = parse("{" + PREFIX + ",\"traceparent\":\"00-ab\",\"data\":{\"z\":2,\"a\":[{\"z\":false,\"a\":null}]}}");
        var b = parse("{\"data\":{\"a\":[{\"a\":null,\"z\":false}],\"z\":2},\"traceparent\":\"00-ab\"," + PREFIX + "}");
        assertEquals(a.canonicalEnvelope(), b.canonicalEnvelope());
        assertTrue(a.canonicalEnvelope().contains("traceparent"));
        assertTrue(parse("{" + PREFIX + ",\"data_base64\":\"aGk=\",\"datacontenttype\":\"text/plain\"}")
                .canonicalEnvelope().contains("aGk="));
        assertNotEquals(parse("{" + PREFIX + "}").canonicalEnvelope(), parse("{" + PREFIX + ",\"data\":null}").canonicalEnvelope());
        assertTrue(parse("{" + PREFIX + ",\"data\":\"{not-an-object}\"}").canonicalEnvelope().contains("{not-an-object}"));
        assertTrue(parse("{" + PREFIX + ",\"data\":1.000000000000000000001}").canonicalEnvelope().contains("1.000000000000000000001"));
    }

    @Test
    void refusesAmbiguousMalformedDeepAndOversizedEnvelopes() {
        for (String value : new String[]{"{}", "[]", "{" + PREFIX + ",\"id\":\"two\"}",
                "{" + PREFIX + "} {}", "{" + PREFIX + ",\"source\":null}",
                "{" + PREFIX + ",\"data\":\"\\uD800\"}",
                "{" + PREFIX + ",\"data\":{},\"data_base64\":\"eA==\"}",
                "{" + PREFIX + ",\"data\":" + "[".repeat(65) + "null" + "]".repeat(65) + "}"}) {
            assertEquals(400, assertThrows(ProblemException.class, () -> parse(value)).getResponse().getStatus());
        }
        assertEquals(413, assertThrows(ProblemException.class,
                () -> parse("{" + PREFIX + ",\"data\":\"" + "x".repeat(StructuredEvent.MAX_BYTES) + "\"}")).getResponse().getStatus());
    }
}
