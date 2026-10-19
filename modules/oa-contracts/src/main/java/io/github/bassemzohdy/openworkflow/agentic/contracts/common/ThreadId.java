package io.github.bassemzohdy.openworkflow.agentic.contracts.common;

import java.util.regex.Pattern;

/**
 * The checkpoint thread name of an agent thread (ADR 0020, decision 1): {@code ws1-} followed by the SHA-256 of a
 * length-prefixed canonical form of (tenant, user, workflow instance). The value is 68 characters long.
 *
 * <p>The thread name is never built by joining raw input. Because each part is length-prefixed before hashing, two
 * different tuples never produce the same canonical form, whatever delimiter characters they contain. The hash then
 * fixes the length and the alphabet. The derivation does not normalise Unicode: canonically equivalent strings give
 * different thread names, which is intended (a scope is the exact text the runtime stored).
 *
 * <p>The value is validated on construction, so a {@code ThreadId} always has the derived shape. It does not prove
 * ownership; the runtime checks the {@code agent_task} row before it reads or resumes a thread (WP-105).
 */
public record ThreadId(String value) {

    /** Prefix that marks the derivation version. */
    public static final String PREFIX = "ws1-";
    /** Length of every thread name: the prefix plus 64 hexadecimal digits. */
    public static final int LENGTH = 68;
    /** Longest accepted tenant, user or workflow instance id. */
    public static final int MAX_PART_LENGTH = 256;

    private static final String DOMAIN = "oa.thread.ws1";
    private static final Pattern FORMAT = Pattern.compile("ws1-[0-9a-f]{64}");

    public ThreadId {
        Checks.requireMatching("thread id", value, FORMAT);
    }

    /** Derives the thread name for a scope. Each part must be non-blank and at most {@link #MAX_PART_LENGTH}. */
    public static ThreadId derive(String tenant, String user, String workflowInstance) {
        Checks.requireText("tenant", tenant, MAX_PART_LENGTH);
        Checks.requireText("user", user, MAX_PART_LENGTH);
        Checks.requireText("workflow instance", workflowInstance, MAX_PART_LENGTH);
        byte[] canonical = new CanonicalEncoder(DOMAIN)
                .text(tenant)
                .text(user)
                .text(workflowInstance)
                .toBytes();
        return new ThreadId(PREFIX + CanonicalEncoder.sha256Hex(canonical));
    }

    /** Checks text received from storage or a message and returns it as a thread id. */
    public static ThreadId parse(String text) {
        return new ThreadId(text);
    }

    @Override
    public String toString() {
        return value;
    }
}
