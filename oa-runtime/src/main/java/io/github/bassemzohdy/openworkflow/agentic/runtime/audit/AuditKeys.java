package io.github.bassemzohdy.openworkflow.agentic.runtime.audit;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * Reserved attribute keys of an audit event. The contract {@code AuditEvent} has no chain, instance, correlation,
 * version or payload fields, so these live in its attributes. Because {@code AuditHash} covers every attribute, the
 * hash covers them too: changing a version stamp or a payload reference breaks the chain.
 *
 * <ul>
 *   <li>{@code oa.chain}: chain id (default {@value #DEFAULT_CHAIN}). Sequence numbers are per chain.</li>
 *   <li>{@code oa.tenant}, {@code oa.instance}, {@code oa.correlation}: optional scope and request identifiers.</li>
 *   <li>{@code oa.payload.form}, {@code oa.payload.ref}, {@code oa.payload.digest}: how the payload is kept.</li>
 *   <li>{@code stamp.*}: version stamps, see {@link VersionStamps}.</li>
 *   <li>{@code field.<path>}: a payload field kept inline or as a salted hash (STORE_INLINE, STORE_REDACTED).</li>
 * </ul>
 */
public final class AuditKeys {

    public static final String CHAIN = "oa.chain";
    public static final String TENANT = "oa.tenant";
    public static final String INSTANCE = "oa.instance";
    public static final String CORRELATION = "oa.correlation";
    public static final String PAYLOAD_FORM = "oa.payload.form";
    public static final String PAYLOAD_REF = "oa.payload.ref";
    public static final String PAYLOAD_DIGEST = "oa.payload.digest";

    public static final String DEFAULT_CHAIN = "default";
    public static final String STAMP_PREFIX = "stamp.";
    public static final String FIELD_PREFIX = "field.";

    static final Pattern CHAIN_ID = Pattern.compile("[A-Za-z0-9._:-]{1,128}");

    private AuditKeys() {
    }

    /** The chain named by the attributes; {@link #DEFAULT_CHAIN} when absent. Throws on an invalid chain id. */
    public static String chainOf(Map<String, String> attributes) {
        String chain = attributes.getOrDefault(CHAIN, DEFAULT_CHAIN);
        if (!CHAIN_ID.matcher(chain).matches()) {
            throw new IllegalArgumentException("oa.chain must match [A-Za-z0-9._:-]{1,128}");
        }
        return chain;
    }
}
