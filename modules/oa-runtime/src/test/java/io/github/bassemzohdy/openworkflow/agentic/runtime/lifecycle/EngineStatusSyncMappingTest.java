package io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle;

import static io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.InstanceStatus.CANCELLED;
import static io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.InstanceStatus.CANCEL_REQUESTED;
import static io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.InstanceStatus.COMPLETED;
import static io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.InstanceStatus.FAILED;
import static io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.InstanceStatus.RUNNING;
import static io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.InstanceStatus.STARTING;
import static io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.InstanceStatus.WAITING;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The table that carries engine reports into the registry (WP-105c, scope item 3). Every step it returns must be a
 * legal registry transition, and CANCELLED must come only from an engine report.
 */
class EngineStatusSyncMappingTest {

    @Test
    void everyPlannedStepIsALegalTransition() {
        for (InstanceStatus current : InstanceStatus.values()) {
            for (EngineStatus engine : EngineStatus.values()) {
                InstanceStatus from = current;
                for (InstanceStatus next : EngineStatusSync.steps(current, engine)) {
                    assertTrue(InstanceStateMachine.canMove(from, next),
                            "planned step " + from + " -> " + next + " for engine " + engine + " is not legal");
                    from = next;
                }
            }
        }
    }

    @Test
    void terminalRowsNeverChange() {
        for (InstanceStatus terminal : List.of(COMPLETED, FAILED, CANCELLED)) {
            for (EngineStatus engine : EngineStatus.values()) {
                assertEquals(List.of(), EngineStatusSync.steps(terminal, engine));
            }
        }
    }

    @Test
    void aWaitingInstanceIsRecordedAsWaitingAndAResumedOneAsRunning() {
        assertEquals(List.of(WAITING), EngineStatusSync.steps(RUNNING, EngineStatus.WAITING));
        assertEquals(List.of(RUNNING), EngineStatusSync.steps(WAITING, EngineStatus.RUNNING));
    }

    @Test
    void aStartingRowIsMovedToRunningBeforeAnyLaterStateSoTheTransitionIsLegal() {
        assertEquals(List.of(RUNNING, WAITING), EngineStatusSync.steps(STARTING, EngineStatus.WAITING));
        assertEquals(List.of(RUNNING, COMPLETED), EngineStatusSync.steps(STARTING, EngineStatus.COMPLETED));
        assertEquals(List.of(RUNNING, FAILED), EngineStatusSync.steps(STARTING, EngineStatus.FAULTED));
    }

    @Test
    void cancelledIsRecordedOnlyWhenTheEngineReportsIt() {
        // A cancel the registry asked for: the engine's report completes it.
        assertEquals(List.of(CANCELLED), EngineStatusSync.steps(CANCEL_REQUESTED, EngineStatus.CANCELLED));
        // A cancel the engine did by itself: the request is recorded first, then the report.
        assertEquals(List.of(CANCEL_REQUESTED, CANCELLED), EngineStatusSync.steps(RUNNING, EngineStatus.CANCELLED));
        // A pending or running report never produces CANCELLED on its own.
        for (EngineStatus engine : List.of(EngineStatus.PENDING, EngineStatus.RUNNING, EngineStatus.WAITING)) {
            assertTrue(!EngineStatusSync.steps(CANCEL_REQUESTED, engine).contains(CANCELLED));
        }
    }

    @Test
    void aCancelRequestedRowFinishesWhenTheEngineCompletesIt() {
        assertEquals(List.of(COMPLETED), EngineStatusSync.steps(CANCEL_REQUESTED, EngineStatus.COMPLETED));
        assertEquals(List.of(FAILED), EngineStatusSync.steps(CANCEL_REQUESTED, EngineStatus.FAULTED));
    }

    @Test
    void theFlowStatusNamesMapOneToOne() {
        for (io.serverlessworkflow.impl.WorkflowStatus status : io.serverlessworkflow.impl.WorkflowStatus.values()) {
            assertEquals(status.name(), FlowEngineObserver.toEngine(status).name());
        }
    }
}
