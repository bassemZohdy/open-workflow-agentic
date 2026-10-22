package io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle;

import static org.junit.jupiter.api.Assertions.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.ResearchOutputFingerprint;
import io.github.bassemzohdy.openworkflow.agentic.runtime.agent.ResearchState;
import java.util.*;
import org.bsc.langgraph4j.serializer.std.ObjectStreamStateSerializer;
import org.junit.jupiter.api.Test;

/** Interoperates with ResearchAgent's actual pinned serializer; no graph, SQL or Java deserialization is invoked. */
class ResearchOutputEncodingTest {
    @Test void actualObjectStreamStateIsCanonicalStoredBase64AcceptedByTheSharedFingerprint() throws Exception {
        var serializer=new ObjectStreamStateSerializer<>(ResearchState::new);
        String stored=serializer.writeDataAsString(new ResearchState(Map.of(ResearchState.TOPIC,"fixture",ResearchState.PLAN,"first")));
        assertEquals(ResearchOutputFingerprint.CONTENT_TYPE,serializer.contentType());
        byte[] decoded=Base64.getDecoder().decode(stored);
        assertArrayEquals(new byte[]{(byte)0xac,(byte)0xed,0,5},Arrays.copyOf(decoded,4));
        assertEquals(stored,Base64.getEncoder().encodeToString(decoded));
        String hash=ResearchOutputFingerprint.of(serializer.contentType(),stored);
        assertTrue(hash.matches("[0-9a-f]{64}"));
        String changed=serializer.writeDataAsString(new ResearchState(Map.of(ResearchState.TOPIC,"fixture",ResearchState.PLAN,"second")));
        assertNotEquals(hash,ResearchOutputFingerprint.of(serializer.contentType(),changed));
        assertEquals(hash,ResearchOutputFingerprint.of(serializer.contentType(),stored));
    }
}
