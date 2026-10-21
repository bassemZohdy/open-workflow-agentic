package io.github.bassemzohdy.openworkflow.agentic.identity;

import java.util.HashMap;
import java.util.Map;

/** The fake issuer of {@link FakeIdpResource}, plus the audience of the governed agent-run resource (WP-103). */
public class AgentFakeIdpResource extends FakeIdpResource {

    public static final String AGENT_AUDIENCE = "oa-agent-run";

    @Override
    public Map<String, String> start() {
        Map<String, String> config = new HashMap<>(super.start());
        config.put("agentic.identity.agent-resource-audience", AGENT_AUDIENCE);
        return config;
    }
}
