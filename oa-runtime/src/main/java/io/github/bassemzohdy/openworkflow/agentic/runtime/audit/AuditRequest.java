package io.github.bassemzohdy.openworkflow.agentic.runtime.audit;

import io.github.bassemzohdy.openworkflow.agentic.contracts.common.Checks;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.Subject;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * What {@link AuditRecorder} needs to record one event: the envelope (chain, tenant, instance, correlation), the
 * actor, the action and the resource, the version stamps in force, and the payload that the retention rule for the
 * action will keep, redact or reference. Optional envelope fields may be null and are then not written.
 */
public record AuditRequest(String chainId, String tenantId, String instanceId, String correlationId, Subject actor,
                           String action, String resourceType, String resourceId, VersionStamps stamps,
                           Map<String, String> payload) {

    public AuditRequest {
        Objects.requireNonNull(actor, "actor");
        action = Checks.requireText("action", action, 512);
        resourceType = Checks.requireText("resourceType", resourceType, 512);
        resourceId = Checks.requireText("resourceId", resourceId, 512);
        Objects.requireNonNull(stamps, "stamps");
        payload = Map.copyOf(Objects.requireNonNull(payload, "payload"));
        if (chainId != null) {
            AuditKeys.requireChainId(chainId);
        }
    }

    /** A request with no envelope, no stamps and no payload. Use the {@code with*} methods to add them. */
    public static AuditRequest of(Subject actor, String action, String resourceType, String resourceId) {
        return new AuditRequest(null, null, null, null, actor, action, resourceType, resourceId, VersionStamps.NONE,
                Map.of());
    }

    public AuditRequest inChain(String chain) {
        return new AuditRequest(chain, tenantId, instanceId, correlationId, actor, action, resourceType, resourceId,
                stamps, payload);
    }

    public AuditRequest forTenant(String tenant) {
        return new AuditRequest(chainId, tenant, instanceId, correlationId, actor, action, resourceType, resourceId,
                stamps, payload);
    }

    public AuditRequest forInstance(String instance) {
        return new AuditRequest(chainId, tenantId, instance, correlationId, actor, action, resourceType, resourceId,
                stamps, payload);
    }

    public AuditRequest withCorrelation(String correlation) {
        return new AuditRequest(chainId, tenantId, instanceId, correlation, actor, action, resourceType, resourceId,
                stamps, payload);
    }

    public AuditRequest withStamps(VersionStamps versions) {
        return new AuditRequest(chainId, tenantId, instanceId, correlationId, actor, action, resourceType, resourceId,
                versions, payload);
    }

    public AuditRequest withPayload(Map<String, String> fields) {
        return new AuditRequest(chainId, tenantId, instanceId, correlationId, actor, action, resourceType, resourceId,
                stamps, fields);
    }

    /** The attributes that describe the envelope, the stamps and nothing else; used by the recorder. */
    Map<String, String> envelope() {
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put(AuditKeys.CHAIN, chainId == null ? AuditKeys.DEFAULT_CHAIN : chainId);
        putIfPresent(attributes, AuditKeys.TENANT, tenantId);
        putIfPresent(attributes, AuditKeys.INSTANCE, instanceId);
        putIfPresent(attributes, AuditKeys.CORRELATION, correlationId);
        attributes.putAll(stamps.toAttributes());
        return attributes;
    }

    private static void putIfPresent(Map<String, String> attributes, String key, String value) {
        if (value != null) {
            attributes.put(key, value);
        }
    }
}
