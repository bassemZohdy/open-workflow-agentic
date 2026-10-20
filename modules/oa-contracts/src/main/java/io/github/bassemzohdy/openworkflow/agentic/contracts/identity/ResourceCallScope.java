package io.github.bassemzohdy.openworkflow.agentic.contracts.identity;

import io.github.bassemzohdy.openworkflow.agentic.contracts.common.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Immutable attribution of one governed resource invocation, supplied by the trusted dispatcher, never request body
 * fields. logicalOccurrence pins the complete enclosing loop/foreach occurrence; retry/resume/owner epochs and token
 * ids are not occurrences. Data only: the consumer still checks the current registry/full owner, exact issuing grant,
 * token expiry/replay, registered target, policy/audit and dispatch ownership before acting.
 */
public record ResourceCallScope(String tenant,Ulid instanceId,Subject owner,String applicationId,String engineInstanceId,
                                String namespace,String definition,String version,String taskPosition,int iteration,
                                String logicalOccurrence,String audience,String operation,String targetFingerprint,String requestFingerprint) {
    private static final Pattern SHA256=Pattern.compile("[0-9a-f]{64}");
    public ResourceCallScope {
        tenant=text("tenant",tenant,256);Objects.requireNonNull(instanceId);Objects.requireNonNull(owner);
        CanonicalEncoder.strictUtf8(owner.id());
        applicationId=text("application",applicationId,256);engineInstanceId=text("engine instance",engineInstanceId,256);
        namespace=text("namespace",namespace,256);definition=text("definition",definition,256);version=text("version",version,128);
        taskPosition=text("task position",taskPosition,1024);
        if(iteration<1) throw new IllegalArgumentException("task iteration must be positive");
        logicalOccurrence=text("logical occurrence",logicalOccurrence,1024);
        audience=text("resource audience",audience,300);operation=text("resource operation",operation,256);
        targetFingerprint=Checks.requireMatching("approved target configuration fingerprint",targetFingerprint,SHA256);
        requestFingerprint=Checks.requireMatching("request fingerprint",requestFingerprint,SHA256);
    }
    private static String text(String field,String value,int max) {
        String result=Checks.requireText(field,value,max);CanonicalEncoder.strictUtf8(result);return result;
    }
    /**
     * Stable invocation identity. Target/request deliberately excluded: a durable store must reject their change for
     * this same key, including when a retry obtains a new credential. A new payload must never silently create a new call.
     */
    public String key() {
        return "rc1-"+CanonicalEncoder.sha256Hex(new CanonicalEncoder("oa.resource.invocation.v1")
                .text(tenant).text(instanceId.value()).text(owner.kind().name()).text(owner.id())
                .text(applicationId).text(engineInstanceId).text(namespace).text(definition).text(version)
                .text(taskPosition).number(iteration).text(logicalOccurrence).toBytes());
    }
    /** The immutable target/request contract bound to key(). This fingerprint is not authorization. */
    public String fingerprint() {
        return CanonicalEncoder.sha256Hex(new CanonicalEncoder("oa.resource.invocation-contract.v1")
                .text(key()).text(audience).text(operation).text(targetFingerprint).text(requestFingerprint).toBytes());
    }
    /** Separate governed namespace, fixed length and complete subject kind/id; never a caller-selected visible id. */
    public ThreadId threadId() {
        String ownerPart="s1-"+CanonicalEncoder.sha256Hex(new CanonicalEncoder("oa.thread.subject")
                .text(owner.kind().name()).text(owner.id()).toBytes());
        return ThreadId.derive(tenant,ownerPart,"governed:"+key());
    }
    /** Exact approved canonical request text; caller owns strict parsing/duplicate refusal/canonicalization. No bearer. */
    public static String requestFingerprint(String canonicalRequest) {
        Checks.requireText("canonical resource request",canonicalRequest,65536);
        if(CanonicalEncoder.strictUtf8(canonicalRequest).length>65536) throw new IllegalArgumentException("resource request exceeds 64 KiB");
        return CanonicalEncoder.sha256Hex(new CanonicalEncoder("oa.resource.request.v1").text(canonicalRequest).toBytes());
    }
}
