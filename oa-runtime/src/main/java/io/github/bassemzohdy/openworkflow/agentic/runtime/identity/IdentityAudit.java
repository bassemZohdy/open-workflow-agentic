package io.github.bassemzohdy.openworkflow.agentic.runtime.identity;

import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.AuditEvent;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.UlidGenerator;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.Subject;
import io.github.bassemzohdy.openworkflow.agentic.runtime.audit.AuditDraft;
import io.github.bassemzohdy.openworkflow.agentic.runtime.audit.AuditKeys;
import io.github.bassemzohdy.openworkflow.agentic.runtime.audit.AuditSink;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Writes the audit events of the identity package. Attributes carry identifiers and decisions, never a token, a secret
 * or a claim value that is not an identifier. Callers pass the attributes of one event; this class adds the tenant and
 * correlation keys and refuses a value that looks like a credential (a compact JWS).
 */
public final class IdentityAudit {

    /** Resource type of grant events. */
    public static final String GRANT = "delegation-grant";
    /** Resource type of events about a workflow instance's authority. */
    public static final String INSTANCE_AUTHORITY = "instance-authority";
    /** Resource type of events about a token presented to a resource. */
    public static final String TOKEN = "token";
    /** Resource type of events about a secret (the name only). */
    public static final String SECRET = "secret";

    private static final java.util.regex.Pattern JWS = java.util.regex.Pattern.compile(
            "[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]*");

    private final AuditSink sink;
    private final Clock clock;
    private final UlidGenerator ids;

    public IdentityAudit(AuditSink sink, Clock clock, UlidGenerator ids) {
        this.sink = Objects.requireNonNull(sink, "sink");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.ids = Objects.requireNonNull(ids, "ids");
    }

    /**
     * Records one event.
     *
     * @throws RuntimeException the sink's failure: the event was not recorded
     */
    public void record(Subject actor, String action, String resourceType, String resourceId, String tenant,
                       String correlationId, Map<String, String> attributes) {
        Map<String, String> all = new LinkedHashMap<>();
        if (tenant != null) {
            all.put(AuditKeys.TENANT, tenant);
        }
        if (correlationId != null) {
            all.put(AuditKeys.CORRELATION, correlationId);
        }
        for (Map.Entry<String, String> entry : attributes.entrySet()) {
            if (entry.getValue() == null) {
                continue;
            }
            String value = entry.getValue();
            if (JWS.matcher(value).matches()) {
                throw new IllegalArgumentException("audit attribute " + entry.getKey() + " looks like a token");
            }
            all.put(entry.getKey(), value.length() > 512 ? value.substring(0, 512) : value);
        }
        AuditEvent event = AuditDraft.of(ids.next(), clock.instant().truncatedTo(ChronoUnit.MICROS), actor, action,
                resourceType, resourceId, all);
        sink.record(event);
    }

    /** A sorted, comma-separated scope list that fits an attribute, or its digest when it does not. */
    public static Map<String, String> scopeAttributes(Set<String> scopes) {
        String joined = scopes.stream().sorted().collect(Collectors.joining(","));
        Map<String, String> out = new LinkedHashMap<>();
        out.put("grant.scope-count", Integer.toString(scopes.size()));
        if (joined.length() <= 400) {
            out.put("grant.scopes", joined);
        } else {
            out.put("grant.scopes-sha256", sha256(joined));
        }
        return out;
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
