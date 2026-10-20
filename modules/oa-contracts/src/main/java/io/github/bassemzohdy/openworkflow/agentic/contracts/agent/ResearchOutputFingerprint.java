package io.github.bassemzohdy.openworkflow.agentic.contracts.agent;

import io.github.bassemzohdy.openworkflow.agentic.contracts.common.CanonicalEncoder;
import java.util.Base64;
import java.util.Objects;

/**
 * Exact retained representation of the interim research graph, not semantic output, completion proof or authority.
 * Pinned PostgresSaver stores standard padded Base64 Java-stream bytes in TEXT. Hash that EXACT stored text, never
 * decoded bytes or a reserialized state. This helper never deserializes Java objects and stores/returns no private text.
 */
public final class ResearchOutputFingerprint {
    public static final String PROFILE="langgraph4j-postgres-saver-1.9.5-v1.1-object-stream";
    public static final String CONTENT_TYPE="application/octet-stream";
    public static final int MAX_ENCODED_BYTES=4*1024*1024;
    private ResearchOutputFingerprint() { }

    /**
     * SHA256 lower hex of CanonicalEncoder domain oa.agent.research.output.v1, text(content type), text(stored Base64).
     * Text is strict UTF-8 preceded by a 4-byte big-endian byte length. Refuse empty, unsupported, oversized or
     * noncanonical encodings. Adapters MUST bound SQL retrieval before loading the private state into memory too.
     */
    public static String of(String contentType,String storedBase64) {
        Objects.requireNonNull(contentType,"contentType");Objects.requireNonNull(storedBase64,"storedBase64");
        if(!CONTENT_TYPE.equals(contentType) || storedBase64.isEmpty() || storedBase64.length()>MAX_ENCODED_BYTES)
            throw new IllegalArgumentException("unsupported or oversized retained graph state");
        try {
            byte[] decoded=Base64.getDecoder().decode(storedBase64);
            if(!Base64.getEncoder().encodeToString(decoded).equals(storedBase64))
                throw new IllegalArgumentException("noncanonical retained graph state");
        } catch(IllegalArgumentException invalid) {
            throw new IllegalArgumentException("invalid retained graph state encoding");
        }
        return CanonicalEncoder.sha256Hex(new CanonicalEncoder("oa.agent.research.output.v1")
                .text(contentType).text(storedBase64).toBytes());
    }
}
