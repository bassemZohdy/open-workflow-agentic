package io.github.bassemzohdy.openworkflow.agentic.runtime.audit;

import io.github.bassemzohdy.openworkflow.agentic.contracts.common.CanonicalEncoder;
import java.util.Map;
import java.util.TreeMap;

/** Digests and redaction values for payloads. Package-private: only the recorder and the store use them. */
final class PayloadProtection {

    static final String REDACTED_PREFIX = "redacted:sha256:";

    private static final String DIGEST_DOMAIN = "oa.audit.payload.v1";
    private static final String REDACTION_DOMAIN = "oa.audit.redaction.v1";

    private PayloadProtection() {
    }

    /** SHA-256 over the canonical, sorted encoding of the payload. Changing any field or key changes the digest. */
    static String digest(Map<String, String> payload) {
        CanonicalEncoder encoder = new CanonicalEncoder(DIGEST_DOMAIN).number(payload.size());
        for (Map.Entry<String, String> entry : new TreeMap<>(payload).entrySet()) {
            encoder.text(entry.getKey()).text(entry.getValue());
        }
        return CanonicalEncoder.sha256Hex(encoder.toBytes());
    }

    /** The value that replaces a redacted field: the salted digest of the path and the value. */
    static String redact(String salt, String path, String value) {
        byte[] input = new CanonicalEncoder(REDACTION_DOMAIN).text(salt).text(path).text(value).toBytes();
        return REDACTED_PREFIX + CanonicalEncoder.sha256Hex(input);
    }
}
