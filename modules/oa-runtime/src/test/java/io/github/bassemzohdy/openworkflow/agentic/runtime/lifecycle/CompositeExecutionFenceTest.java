package io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.ResourceExecutionEntry;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.UlidGenerator;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.ResourceCallScope;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.Subject;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.SubjectKind;
import io.github.bassemzohdy.openworkflow.agentic.contracts.port.ResourceExecutionFence;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.CompositeExecutionFence.Check;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.JdbcResourceExecutionGate.RefusedException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class CompositeExecutionFenceTest {
    private static final ResourceExecutionEntry ENTRY = new ResourceExecutionEntry(new ResourceCallScope("t1",
            UlidGenerator.shared().next(), new Subject(SubjectKind.USER, "u"), "app", "engine-1", "ns", "wf", "1",
            "do/0/x", 1, "root", "aud", "op", "a".repeat(64), ResourceCallScope.requestFingerprint("{}")), 1);

    @AfterEach void clearInterrupt() { Thread.interrupted(); }

    private static CompositeExecutionFence.Builder recording(List<Check> seen, Check failing, RuntimeException failure) {
        var builder = CompositeExecutionFence.builder();
        for (Check check : Check.values())
            builder.with(check, entry -> {
                seen.add(check);
                if (check == failing) throw failure;
            });
        return builder;
    }

    @Test void everyCheckRunsInFixedOrderAndTheEntryIsPassedThrough() {
        var seen = new ArrayList<Check>();
        recording(seen, null, null).build().requireCurrent(ENTRY);
        assertEquals(List.of(Check.values()), seen);
    }

    @Test void aMissingCategoryCannotBuildAndThereIsNoDefaultAllowance() {
        var builder = CompositeExecutionFence.builder();
        for (Check check : Check.values()) {
            if (check == Check.AUDIT) continue;
            builder.with(check, e -> { });
        }
        var e = assertThrows(IllegalStateException.class, builder::build);
        assertTrue(e.getMessage().contains("AUDIT"));
        assertThrows(IllegalStateException.class, () -> CompositeExecutionFence.builder().build());
    }

    @Test void duplicatesAndNullsAreRefusedAtConstruction() {
        var builder = CompositeExecutionFence.builder().with(Check.IDENTITY, e -> { });
        assertThrows(IllegalArgumentException.class, () -> builder.with(Check.IDENTITY, e -> { }));
        assertThrows(NullPointerException.class, () -> builder.with(null, e -> { }));
        assertThrows(NullPointerException.class, () -> builder.with(Check.POLICY, null));
    }

    @Test void aRefusalStopsLaterChecksAndKeepsTheOriginalRefusal() {
        var seen = new ArrayList<Check>();
        var original = new RefusedException("policy revoked");
        var fence = recording(seen, Check.POLICY, original).build();
        assertSame(original, assertThrows(RefusedException.class, () -> fence.requireCurrent(ENTRY)));
        assertEquals(List.of(Check.IDENTITY, Check.POLICY), seen);
    }

    @Test void anUnexpectedFailureRefusesWithoutLeakingDetailIntoTheMessage() {
        var seen = new ArrayList<Check>();
        var fence = recording(seen, Check.OWNER_AND_LEASE, new IllegalStateException("jdbc:postgresql://secret-host/db"))
                .build();
        var e = assertThrows(RefusedException.class, () -> fence.requireCurrent(ENTRY));
        assertFalse(e.getMessage().contains("secret-host"), e.getMessage());
        assertTrue(e.getMessage().contains("OWNER_AND_LEASE"));
        assertInstanceOf(IllegalStateException.class, e.getCause());
        assertEquals(List.of(Check.IDENTITY, Check.POLICY, Check.TASK_AND_TARGET, Check.OWNER_AND_LEASE), seen);
    }

    @Test void anInterruptedThreadRefusesBeforeAnyCheckAndStaysInterrupted() {
        var seen = new ArrayList<Check>();
        var fence = recording(seen, null, null).build();
        Thread.currentThread().interrupt();
        assertThrows(RefusedException.class, () -> fence.requireCurrent(ENTRY));
        assertTrue(Thread.currentThread().isInterrupted(), "the interrupt flag is preserved");
        assertTrue(seen.isEmpty());
    }

    @Test void anInterruptRaisedDuringTheLastCheckStillRefuses() {
        var builder = CompositeExecutionFence.builder();
        for (Check check : Check.values())
            builder.with(check, check == Check.AUDIT ? e -> Thread.currentThread().interrupt() : e -> { });
        var fence = builder.build();
        assertThrows(RefusedException.class, () -> fence.requireCurrent(ENTRY));
        assertTrue(Thread.currentThread().isInterrupted());
    }

    @Test void aNullEntryIsRefused() {
        var fence = recording(new ArrayList<>(), null, null).build();
        assertThrows(NullPointerException.class, () -> fence.requireCurrent(null));
    }

    @Test void theFenceCannotBeSerializedAndRedactsItsDescription() {
        var fence = recording(new ArrayList<>(), null, null).build();
        var mapper = new ObjectMapper().disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
        assertThrows(JsonMappingException.class, () -> mapper.writeValueAsString(fence));
        assertEquals("CompositeExecutionFence[redacted]", fence.toString());
    }

    @Test void itIsADropInTrustedFenceForTheGate() {
        ResourceExecutionFence asFence = recording(new ArrayList<>(), null, null).build();
        assertDoesNotThrow(() -> asFence.requireCurrent(ENTRY));
    }
}
