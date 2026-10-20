package io.github.bassemzohdy.openworkflow.agentic.contracts.governance;

import io.github.bassemzohdy.openworkflow.agentic.contracts.common.Checks;
import io.github.bassemzohdy.openworkflow.agentic.contracts.common.ThreadId;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.Subject;
import java.util.Objects;

/**
 * Attribution of an effect to its authoritative registry instance and owner. This is data, not authorization:
 * consumers must resolve the registry row under the verified tenant and compare the FULL owner (kind and id).
 * Never accept this tuple from a request or workflow as proof. No credential, grant, process or resume epoch is stored.
 */
public record EffectScope(String tenant,Ulid instanceId,Subject owner) {
    public EffectScope {
        tenant=Checks.requireText("tenant",tenant,ThreadId.MAX_PART_LENGTH);
        Objects.requireNonNull(instanceId,"instanceId"); Objects.requireNonNull(owner,"owner");
        ThreadId.derive(tenant,owner.id(),instanceId.value()); // Preserve the existing thread/key derivation exactly.
    }

    /** Existing ws1 thread identity; the full registry owner still must be checked separately. */
    public ThreadId threadId() { return ThreadId.derive(tenant,owner.id(),instanceId.value()); }

    /** Stable logical-operation key. Adapters supply a canonical request WITHOUT credentials. */
    public EffectCommand command(String node,String occurrence,String target,String canonicalRequest) {
        return EffectCommand.of(threadId(),node,occurrence,target,canonicalRequest);
    }

    /** Reject another tuple's key. This check by itself does not authorize a caller or validate registry ownership. */
    public void requireKey(EffectKey key) {
        Objects.requireNonNull(key,"key");
        if(!threadId().equals(key.threadId())) throw new IllegalArgumentException("effect key belongs to another scope");
    }
}
