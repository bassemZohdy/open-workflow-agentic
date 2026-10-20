package io.github.bassemzohdy.openworkflow.agentic.runtime.event;

import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.SubjectKind;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import java.time.Duration;
import java.util.Map;
import java.util.Set;

/** Deployment-approved producer routes. An empty map denies all ingress; event extensions cannot configure it. */
@ConfigMapping(prefix = "agentic.events")
public interface EventIngressSettings {
    @WithDefault("PT24H") Duration retention();
    @WithDefault("policy/events") String policyFolder();
    Map<String, Producer> producers();
    interface Producer {
        String tenant();
        SubjectKind kind();
        String subject();
        String source();
        Set<String> types();
        Set<String> definitions();
        Set<String> correlations();
    }
}
