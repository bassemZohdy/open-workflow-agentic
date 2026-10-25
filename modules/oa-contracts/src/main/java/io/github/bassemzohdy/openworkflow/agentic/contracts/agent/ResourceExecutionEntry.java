package io.github.bassemzohdy.openworkflow.agentic.contracts.agent;

import io.github.bassemzohdy.openworkflow.agentic.contracts.common.CanonicalEncoder;
import io.github.bassemzohdy.openworkflow.agentic.contracts.common.ThreadId;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.ResourceCallScope;
import java.util.Objects;

/**
 * Retained execution attribution, NEVER a capability or permission to invoke a graph. The durable gate owns one
 * irreversible fresh NO_ENTRY -> ENTERED transition per call key, and only its newly acknowledged callback may enter.
 * A constructed/replayed entry, new credential, expired lease, empty checkpoint store or uncertain commit reply
 * cannot authorize another run. Compare the FULL scope, not just a hash. Epochs are not business occurrences.
 */
public record ResourceExecutionEntry(ResourceCallScope scope,long epoch) {
    public ResourceExecutionEntry {
        Objects.requireNonNull(scope,"scope");
        if(epoch<1) throw new IllegalArgumentException("execution epoch must be positive");
    }
    public String callKey() {return scope.key();}
    public ThreadId threadId() {return scope.threadId();}
    /** Immutable data fingerprint; stores must still serialize by callKey(), never use a new epoch as a fresh call. */
    public String fingerprint() {
        return "re1-"+CanonicalEncoder.sha256Hex(new CanonicalEncoder("oa.resource.execution-entry.v1")
                .text(scope.key()).text(scope.fingerprint()).number(epoch).toBytes());
    }
}
