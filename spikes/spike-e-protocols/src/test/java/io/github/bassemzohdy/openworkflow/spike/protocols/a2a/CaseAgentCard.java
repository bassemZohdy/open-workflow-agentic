package io.github.bassemzohdy.openworkflow.spike.protocols.a2a;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import java.util.List;
import java.util.Map;
import org.a2aproject.sdk.server.PublicAgentCard;
import org.a2aproject.sdk.spec.AgentCapabilities;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.AgentInterface;
import org.a2aproject.sdk.spec.AgentSkill;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** Publishes the Agent Card at /.well-known/agent-card.json. */
@ApplicationScoped
public class CaseAgentCard {

    @ConfigProperty(name = "quarkus.http.test-port", defaultValue = "8081")
    int port;

    @Produces
    @PublicAgentCard
    public AgentCard agentCard() {
        String url = "http://localhost:" + port + "/";
        return AgentCard.builder()
                .name("case-agent")
                .description("Spike E toy agent: answers case questions")
                .version("0.1.0")
                .capabilities(AgentCapabilities.builder().streaming(true).pushNotifications(false).build())
                .defaultInputModes(List.of("text/plain"))
                .defaultOutputModes(List.of("text/plain"))
                .skills(List.of(AgentSkill.builder()
                        .id("case-status")
                        .name("Case status")
                        .description("Looks up the status of a case")
                        .tags(List.of("case"))
                        .build()))
                .supportedInterfaces(List.of(new AgentInterface("JSONRPC", url)))
                .url(url)
                .preferredTransport("JSONRPC")
                .build();
    }
}
