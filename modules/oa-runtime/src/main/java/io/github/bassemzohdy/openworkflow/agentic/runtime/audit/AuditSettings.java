package io.github.bassemzohdy.openworkflow.agentic.runtime.audit;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import java.util.Map;
import java.util.Optional;

/**
 * Configuration of the audit trail (WP-111). Example:
 *
 * <pre>
 * agentic.audit.default-form=STORE_REFERENCE_ONLY
 * agentic.audit.redaction-salt=${AUDIT_REDACTION_SALT}
 * agentic.audit.rules."customer.updated".form=STORE_REDACTED
 * agentic.audit.rules."customer.updated".redacted-paths=customer.iban,customer.phone
 * </pre>
 */
@ConfigMapping(prefix = "agentic.audit")
public interface AuditSettings {

    /** The form for event types without a rule. Reference only is the safe default. */
    @WithDefault("STORE_REFERENCE_ONLY")
    RetentionForm defaultForm();

    /** Salt for redacted fields. Required when a rule or the default redacts. Keep it out of the repository. */
    Optional<String> redactionSalt();

    /** Rules by event type (the action of the event). */
    Map<String, Rule> rules();

    /** Cron expression of the scheduled verification of every chain (used by {@code AuditVerificationJob}). */
    @WithDefault("0 17 * * * ?")
    String verifyCron();

    interface Rule {

        RetentionForm form();

        /** Comma-separated field paths for STORE_REDACTED. */
        Optional<String> redactedPaths();
    }
}
