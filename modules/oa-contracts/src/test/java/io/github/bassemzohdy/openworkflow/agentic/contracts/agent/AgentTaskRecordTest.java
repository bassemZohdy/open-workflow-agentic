package io.github.bassemzohdy.openworkflow.agentic.contracts.agent;

import static io.github.bassemzohdy.openworkflow.agentic.contracts.testdata.Samples.TENANT;
import static io.github.bassemzohdy.openworkflow.agentic.contracts.testdata.Samples.TASK_ID;
import static io.github.bassemzohdy.openworkflow.agentic.contracts.testdata.Samples.USER;
import static io.github.bassemzohdy.openworkflow.agentic.contracts.testdata.Samples.WORKFLOW_INSTANCE;
import static io.github.bassemzohdy.openworkflow.agentic.contracts.testdata.Samples.contract;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.bassemzohdy.openworkflow.agentic.contracts.common.ThreadId;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.Subject;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.SubjectKind;
import io.github.bassemzohdy.openworkflow.agentic.contracts.testdata.Samples;
import org.junit.jupiter.api.Test;

class AgentTaskRecordTest {

    private static final ThreadId THREAD = ThreadId.derive(TENANT, USER.id(), WORKFLOW_INSTANCE);

    @Test
    void consistentRecordIsAccepted() {
        AgentTaskRecord record = Samples.taskRecord();
        assertEquals(THREAD, record.threadId());
        assertEquals(AgentTaskStatus.RUNNING, record.status());
    }

    @Test
    void threadMustBeDerivedFromTheScope() {
        assertThrows(IllegalArgumentException.class, () -> new AgentTaskRecord(TASK_ID, TENANT, USER,
                WORKFLOW_INSTANCE, ThreadId.derive("other-tenant", USER.id(), WORKFLOW_INSTANCE), contract(),
                AgentTaskStatus.PENDING, 0L));
    }

    @Test
    void taskIdMustBeTheContractId() {
        assertThrows(IllegalArgumentException.class, () -> new AgentTaskRecord(Samples.CHILD_ID, TENANT, USER,
                WORKFLOW_INSTANCE, THREAD, contract(), AgentTaskStatus.PENDING, 0L));
    }

    @Test
    void ownerMustBeTheRequestingSubject() {
        Subject other = new Subject(SubjectKind.USER, "someone-else");
        assertThrows(IllegalArgumentException.class, () -> new AgentTaskRecord(TASK_ID, TENANT, other,
                WORKFLOW_INSTANCE, ThreadId.derive(TENANT, other.id(), WORKFLOW_INSTANCE), contract(),
                AgentTaskStatus.PENDING, 0L));
    }

    @Test
    void versionIsNeverNegative() {
        assertThrows(IllegalArgumentException.class, () -> new AgentTaskRecord(TASK_ID, TENANT, USER,
                WORKFLOW_INSTANCE, THREAD, contract(), AgentTaskStatus.PENDING, -1L));
    }

    @Test
    void blankScopeIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new AgentTaskRecord(TASK_ID, " ", USER,
                WORKFLOW_INSTANCE, THREAD, contract(), AgentTaskStatus.PENDING, 0L));
    }
}
