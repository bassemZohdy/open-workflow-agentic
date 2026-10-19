package io.github.bassemzohdy.openworkflow.agentic;

import io.quarkus.test.junit.QuarkusTestProfile;
import java.util.Map;

/**
 * Turns on the development-only audit access (agentic.dev.audit-open) and one redaction rule, so that the audit API
 * test also proves the per-type configuration reaches the recorder. The audit API has no anonymous caller (WP-102):
 * dev-open removes the policy requirement only, so the test also trusts the development caller headers, which is what
 * a developer would do too.
 */
public class AuditOpenProfile implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
                "agentic.dev.audit-open", "true",
                "agentic.dev.trust-caller-headers", "true",
                "agentic.audit.redaction-salt", "test-salt-only",
                "agentic.audit.rules.\"customer.updated\".form", "STORE_REDACTED",
                "agentic.audit.rules.\"customer.updated\".redacted-paths", "customer.iban");
    }
}
