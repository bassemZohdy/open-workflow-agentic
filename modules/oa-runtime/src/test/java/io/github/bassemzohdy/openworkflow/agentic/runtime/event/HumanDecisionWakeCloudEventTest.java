package io.github.bassemzohdy.openworkflow.agentic.runtime.event;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bassemzohdy.openworkflow.agentic.contracts.event.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.HumanDecision;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.*;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class HumanDecisionWakeCloudEventTest {
    @Test void referenceEnvelopeDecodesThroughTheActualInboxCloudEventPath() throws Exception {
        var task = Ulid.parse("01ARZ3NDEKTSV4RRFFQ69G5FAV");
        var decision = new HumanDecision(Ulid.parse("01ARZ3NDEKTSV4RRFFQ69G5FAW"), "tenant", task, 1,
                new Subject(SubjectKind.USER, "alice"), HumanDecision.Outcome.REJECT, "private answer");
        var wait = new InboxWait("tenant", Ulid.parse("01ARZ3NDEKTSV4RRFFQ69G5FAX"), "app", "engine", "ns", "def", "1",
                "do/1", 1, HumanDecisionWake.route(task), HumanDecisionWake.TYPE, "0".repeat(64), Duration.ofMinutes(1));
        InboxMessage receipt = HumanDecisionWake.message(decision, wait);
        var parsed = StructuredEvent.read(new ByteArrayInputStream(receipt.dataJson().getBytes(StandardCharsets.UTF_8)));
        assertEquals(receipt.dataJson(), parsed.canonicalEnvelope(), "retries retain the canonical CloudEvent bytes");
        var event = StoredCloudEventDecoder.decode(receipt);
        assertEquals(decision.id().value(), event.getId());
        assertEquals(HumanDecisionWake.SOURCE, event.getSource().toString());
        assertEquals(HumanDecisionWake.TYPE, event.getType());
        var data = new ObjectMapper().readTree(event.getData().toBytes());
        assertEquals(decision.id().value(), data.path("decisionId").asText());
        assertEquals(wait.digest(), data.path("waitDigest").asText());
        assertFalse(data.toString().contains("private"));
        assertFalse(data.toString().contains("alice"));
        assertFalse(data.toString().contains("REJECT"));

        var forged = new InboxMessage(receipt.tenant(), receipt.source(), "different-id", receipt.type(), receipt.instanceId(),
                receipt.correlationKey(), receipt.dataJson());
        assertThrows(IllegalStateException.class, () -> StoredCloudEventDecoder.decode(forged));
    }
}
