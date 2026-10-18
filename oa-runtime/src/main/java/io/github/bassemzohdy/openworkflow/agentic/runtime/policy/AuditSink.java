package io.github.bassemzohdy.openworkflow.agentic.runtime.policy;

/**
 * Port: receives every policy decision the enforcement point makes, refusals included (M12, M15). WP-111 provides the
 * hash-chained implementation; until then {@link LoggingAuditSink} is the default.
 *
 * <p><b>Failure semantics.</b> A decision that cannot be recorded is not allowed to proceed. If {@link #record}
 * throws, {@link Pep} returns an ERROR decision in place of the original one, so an unrecorded allow never reaches a
 * guarded call.
 */
@FunctionalInterface
public interface AuditSink {

    void record(PolicyDecisionRecord record);
}
