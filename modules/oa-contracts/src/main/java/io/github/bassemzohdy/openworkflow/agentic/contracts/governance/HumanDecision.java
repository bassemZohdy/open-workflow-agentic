package io.github.bassemzohdy.openworkflow.agentic.contracts.governance;

import io.github.bassemzohdy.openworkflow.agentic.contracts.common.CanonicalEncoder;
import io.github.bassemzohdy.openworkflow.agentic.contracts.common.Checks;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.Subject;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.SubjectKind;
import java.util.Objects;

/**
 * Identity and exact content of a proposed human decision (WP-106). This value is data, not authorization or proof
 * that the actor is a verified human. The service must obtain the actor from the verified caller and authorize the
 * current retained task facts before a new mutation. It must persist the decision and its wakeup atomically.
 *
 * <p>Retries use the same decision id and the original expected task version. A store must compare the fingerprint
 * for that id: identical content may return the retained result, while changed content conflicts. The fingerprint is
 * not a secret or an audit payload; even a digest of private decision content can be sensitive.
 */
public record HumanDecision(Ulid id, String tenant, Ulid taskId, long expectedVersion, Subject actor,
                            Outcome outcome, String content) {

    public static final int MAX_CONTENT_LENGTH = 16_384;

    public enum Outcome { APPROVE, REJECT }

    public HumanDecision {
        Objects.requireNonNull(id, "id");
        tenant = Checks.requireText("tenant", tenant, 256);
        Objects.requireNonNull(taskId, "taskId");
        if (expectedVersion < 0) throw new IllegalArgumentException("expectedVersion must not be negative");
        Objects.requireNonNull(actor, "actor");
        if (actor.kind() != SubjectKind.USER) throw new IllegalArgumentException("decision actor must be a user");
        Objects.requireNonNull(outcome, "outcome");
        if (content != null && content.length() > MAX_CONTENT_LENGTH) {
            throw new IllegalArgumentException("decision content is too long");
        }
        CanonicalEncoder.strictUtf8(tenant);
        CanonicalEncoder.strictUtf8(actor.id());
        if (content != null) CanonicalEncoder.strictUtf8(content);
    }

    /** The exact request identity; null and empty content are different. Do not treat this as authority. */
    public String fingerprint() {
        return CanonicalEncoder.sha256Hex(new CanonicalEncoder("oa.human.decision.v1")
                .text(id.value()).text(tenant).text(taskId.value()).number(expectedVersion)
                .text(actor.kind().name()).text(actor.id()).text(outcome.name()).optionalText(content).toBytes());
    }

    @Override
    public String toString() {
        return "HumanDecision[id=" + id.value() + ", task=" + taskId.value() + ", outcome=" + outcome + "]";
    }
}
