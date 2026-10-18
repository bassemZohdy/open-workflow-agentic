package spikeb;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Candidate encodings of a checkpoint thread id from (tenant, user, workflow instance).
 * The naive form joins with a delimiter. The proposed form length-prefixes each part and hashes the
 * canonical tuple, so the id has a fixed length and cannot collide through delimiters.
 */
public record ThreadScope(String tenant, String user, String instance) {

    public ThreadScope {
        if (tenant == null || user == null || instance == null) {
            throw new IllegalArgumentException("scope parts cannot be null");
        }
    }

    /** Naive encoding: what a developer would write first. Not safe. */
    public String naiveThreadId() {
        return tenant + "|" + user + "|" + instance;
    }

    /** Proposed encoding: fixed length, injective over the tuple. */
    public String threadId() {
        String canonical = "ws1" + part(tenant) + part(user) + part(instance);
        return "ws1-" + sha256Hex(canonical);
    }

    static String part(String value) {
        return value.length() + ":" + value;
    }

    static String sha256Hex(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
