package io.github.bassemzohdy.openworkflow.agentic.runtime.policy;

import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.AuditEvent;
import io.github.bassemzohdy.openworkflow.agentic.runtime.audit.AuditSink;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.AuthorizationRequest;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.Decision;
import io.github.bassemzohdy.openworkflow.agentic.contracts.port.PolicyDecisionPoint;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** Test doubles for the policy package. */
final class PolicyDoubles {

    private PolicyDoubles() {
    }

    /** Audit sink that keeps every record in memory. */
    static class InMemoryAuditSink implements AuditSink {

        private final List<AuditEvent> records = new ArrayList<>();

        @Override
        public synchronized void record(AuditEvent record) {
            records.add(record);
        }

        synchronized List<AuditEvent> records() {
            return List.copyOf(records);
        }
    }

    /** Engine double: answers with a scripted function and keeps the requests it received. */
    static final class ScriptedEngine implements PolicyDecisionPoint {

        private final Function<AuthorizationRequest, Decision> behaviour;
        private final List<AuthorizationRequest> received = new ArrayList<>();

        ScriptedEngine(Function<AuthorizationRequest, Decision> behaviour) {
            this.behaviour = behaviour;
        }

        @Override
        public synchronized Decision decide(AuthorizationRequest request) {
            received.add(request);
            return behaviour.apply(request);
        }

        synchronized List<AuthorizationRequest> received() {
            return List.copyOf(received);
        }
    }
}
