package io.github.bassemzohdy.openworkflow.agentic.runtime.audit;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.time.Clock;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import javax.sql.DataSource;

/**
 * CDI wiring of the audit trail. The classes themselves have no CDI annotations and can be built without a container.
 */
@ApplicationScoped
public class AuditProducers {

    @Inject
    AuditSettings settings;

    @Produces
    @Singleton
    public RetentionPolicy retentionPolicy() {
        Map<String, RetentionPolicy.Rule> rules = new HashMap<>();
        for (Map.Entry<String, AuditSettings.Rule> entry : settings.rules().entrySet()) {
            Set<String> paths = entry.getValue().redactedPaths()
                    .map(text -> Arrays.stream(text.split(","))
                            .map(String::trim)
                            .filter(path -> !path.isEmpty())
                            .collect(Collectors.toSet()))
                    .orElseGet(Set::of);
            rules.put(entry.getKey(), new RetentionPolicy.Rule(entry.getValue().form(), paths));
        }
        Optional<String> salt = settings.redactionSalt();
        return RetentionPolicy.of(settings.defaultForm(), rules, salt.orElse(null));
    }

    @Produces
    @Singleton
    public AuditTrail auditTrail(DataSource dataSource) {
        return new AuditTrail(dataSource);
    }

    @Produces
    @Singleton
    public AuditSink auditSink(AuditTrail trail) {
        return trail;
    }

    @Produces
    @Singleton
    public PayloadStore payloadStore(DataSource dataSource) {
        return new JdbcPayloadStore(dataSource);
    }

    @Produces
    @Singleton
    public AuditRecorder auditRecorder(AuditTrail trail, PayloadStore payloads, RetentionPolicy retention) {
        return new AuditRecorder(trail, payloads, retention, Clock.systemUTC());
    }

    @Produces
    @Singleton
    public ChainVerifier chainVerifier(DataSource dataSource) {
        return new ChainVerifier(dataSource);
    }

    @Produces
    @Singleton
    public AuditQuery auditQuery(DataSource dataSource) {
        return new AuditQuery(dataSource);
    }
}
