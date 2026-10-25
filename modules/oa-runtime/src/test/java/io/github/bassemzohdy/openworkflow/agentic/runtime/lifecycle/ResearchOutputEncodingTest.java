package io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle;

import static org.junit.jupiter.api.Assertions.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.ResearchOutputFingerprint;
import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.ResourceExecutionEntry;
import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.ResourceExecutionOutcome;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.ResourceCallScope;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.Subject;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.SubjectKind;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid;
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
        var scope=new ResourceCallScope("tenant",Ulid.parse("01ARZ3NDEKTSV4RRFFQ69G5FAV"),
                new Subject(SubjectKind.USER,"alice"),"app","engine","agentic","research","1.0.0",
                "/do/0/research",1,"serializer-fixture","research-audience","agent:research:run",
                "a".repeat(64),"b".repeat(64));
        var entry=new ResourceExecutionEntry(scope,1);
        var run=UUID.fromString("19b2ca22-659a-4904-b297-c8ddab7b1de7");
        var checkpoint=UUID.fromString("4d70a2e6-6ec8-43ab-b7ea-2304966a385b");
        var outcome=new ResourceExecutionOutcome(entry,ResearchOutputFingerprint.PROFILE,run,checkpoint,
                serializer.contentType(),hash);
        outcome.requireMatches(entry,scope.threadId(),run,checkpoint,serializer.contentType(),stored);
        assertThrows(IllegalStateException.class,()->outcome.requireMatches(entry,scope.threadId(),run,
                checkpoint,serializer.contentType(),changed));
    }
}
