package io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.ResourceExecutionEntry;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.UlidGenerator;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.ResourceCallScope;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.Subject;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.SubjectKind;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.JdbcResourceExecutionGate.RefusedException;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class InstanceRegistryFenceTest {
    private static final Instant NOW = Instant.parse("2026-11-01T10:00:00Z");
    private static final Subject OWNER = new Subject(SubjectKind.USER, "alice");

    private final Ulid instanceId = UlidGenerator.shared().next();
    private final ResourceExecutionEntry entry = new ResourceExecutionEntry(new ResourceCallScope("t1", instanceId, OWNER,
            "app", "engine-1", "ns", "wf", "1", "do/0/x", 1, "root", "aud", "op", "a".repeat(64),
            ResourceCallScope.requestFingerprint("{}")), 1);
    private Optional<InstanceRecord> row;
    private RuntimeException lookupFailure;
    private int lookups;
    private InstanceRegistryFence fence;

    private InstanceRecord record(InstanceStatus status, Subject owner, String engineId, DefinitionRef definition) {
        return new InstanceRecord(instanceId, "t1", owner, "key-1", "b".repeat(64), definition, Map.of(), status,
                engineId, 1, null, null, null, 1L, NOW, NOW);
    }

    private InstanceRecord running() {
        return record(InstanceStatus.RUNNING, OWNER, "engine-1", new DefinitionRef("ns/wf", "1"));
    }

    @BeforeEach void setUp() {
        row = Optional.of(running());
        fence = new InstanceRegistryFence((tenant, id) -> {
            lookups++;
            assertEquals("t1", tenant);
            assertEquals(instanceId, id);
            if (lookupFailure != null) throw lookupFailure;
            return row;
        });
    }

    private void assertRefused(String reason) {
        var e = assertThrows(RefusedException.class, () -> fence.requireCurrent(entry));
        assertTrue(e.getMessage().contains(reason), e.getMessage());
    }

    @Test void aRunningInstanceThatAgreesWithTheScopePasses() {
        assertDoesNotThrow(() -> fence.requireCurrent(entry));
    }

    @Test void theRowIsReadAfreshOnEveryCall() {
        fence.requireCurrent(entry);
        fence.requireCurrent(entry);
        assertEquals(2, lookups);
        row = Optional.of(record(InstanceStatus.CANCEL_REQUESTED, OWNER, "engine-1", new DefinitionRef("ns/wf", "1")));
        assertRefused("not running");
    }

    @Test void everyNonRunningStatusRefuses() {
        for (InstanceStatus status : InstanceStatus.values()) {
            if (status == InstanceStatus.RUNNING) continue;
            row = Optional.of(record(status, OWNER, "engine-1", new DefinitionRef("ns/wf", "1")));
            assertRefused("not running");
        }
    }

    @Test void aMissingInstanceRefuses() {
        row = Optional.empty();
        assertRefused("not found");
    }

    @Test void aDifferentOwnerEvenWithTheSameIdRefuses() {
        row = Optional.of(record(InstanceStatus.RUNNING, new Subject(SubjectKind.USER, "mallory"), "engine-1",
                new DefinitionRef("ns/wf", "1")));
        assertRefused("owner differs");
        row = Optional.of(record(InstanceStatus.RUNNING, new Subject(SubjectKind.AGENT, "alice"), "engine-1",
                new DefinitionRef("ns/wf", "1")));
        assertRefused("owner differs");
    }

    @Test void aReplacedOrMissingEngineInstanceRefuses() {
        row = Optional.of(record(InstanceStatus.RUNNING, OWNER, "engine-2", new DefinitionRef("ns/wf", "1")));
        assertRefused("engine instance differs");
        row = Optional.of(record(InstanceStatus.RUNNING, OWNER, null, new DefinitionRef("ns/wf", "1")));
        assertRefused("engine instance differs");
    }

    @Test void aDifferentDefinitionOrVersionRefuses() {
        row = Optional.of(record(InstanceStatus.RUNNING, OWNER, "engine-1", new DefinitionRef("ns/other", "1")));
        assertRefused("definition differs");
        row = Optional.of(record(InstanceStatus.RUNNING, OWNER, "engine-1", new DefinitionRef("ns/wf", "2")));
        assertRefused("definition differs");
        row = Optional.of(record(InstanceStatus.RUNNING, OWNER, "engine-1", new DefinitionRef("other/wf", "1")));
        assertRefused("definition differs");
    }

    @Test void aFailingLookupRefusesAndKeepsTheCauseOutOfTheMessage() {
        lookupFailure = new IllegalStateException("jdbc:postgresql://secret-host/db");
        var e = assertThrows(RefusedException.class, () -> fence.requireCurrent(entry));
        assertFalse(e.getMessage().contains("secret-host"));
        assertSame(lookupFailure, e.getCause());
    }

    @Test void constructionAndInputAreNullChecked() {
        assertThrows(NullPointerException.class, () -> new InstanceRegistryFence(null));
        assertThrows(NullPointerException.class, () -> InstanceRegistryFence.over(null));
        assertThrows(NullPointerException.class, () -> fence.requireCurrent(null));
    }

    @Test void itCannotBeSerializedAndRedactsItsDescription() {
        var mapper = new ObjectMapper().disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
        assertThrows(JsonMappingException.class, () -> mapper.writeValueAsString(fence));
        assertEquals("InstanceRegistryFence[redacted]", fence.toString());
    }

    @Test void itComposesAsTheOwnerCheckOfTheCompositeFence() {
        var composite = CompositeExecutionFence.builder()
                .with(CompositeExecutionFence.Check.IDENTITY, e -> { })
                .with(CompositeExecutionFence.Check.POLICY, e -> { })
                .with(CompositeExecutionFence.Check.TASK_AND_TARGET, e -> { })
                .with(CompositeExecutionFence.Check.OWNER_AND_LEASE, fence)
                .with(CompositeExecutionFence.Check.AUDIT, e -> { })
                .build();
        assertDoesNotThrow(() -> composite.requireCurrent(entry));
        row = Optional.empty();
        assertThrows(RefusedException.class, () -> composite.requireCurrent(entry));
    }
}
