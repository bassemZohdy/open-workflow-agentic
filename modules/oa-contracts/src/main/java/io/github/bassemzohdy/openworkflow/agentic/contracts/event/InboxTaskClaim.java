package io.github.bassemzohdy.openworkflow.agentic.contracts.event;

import io.github.bassemzohdy.openworkflow.agentic.contracts.common.Checks;
import java.util.Objects;
import java.util.regex.Pattern;

/** A fencing token, never an alternative to consulting current database authority. Owner is a runtime nonce. */
public record InboxTaskClaim(InboxTask task, String owner, long epoch) {
    private static final Pattern OWNER=Pattern.compile("[A-Za-z0-9._:-]{1,128}");
    public InboxTaskClaim {
        Objects.requireNonNull(task); owner=Checks.requireMatching("owner",owner,OWNER);
        if(epoch<1) throw new IllegalArgumentException("claim epoch must be positive");
    }
}
