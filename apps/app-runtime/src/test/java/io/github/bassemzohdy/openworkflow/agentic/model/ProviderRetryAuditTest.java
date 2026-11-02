package io.github.bassemzohdy.openworkflow.agentic.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.sun.net.httpserver.HttpServer;
import io.github.bassemzohdy.openworkflow.agentic.runtime.agent.LangChain4jLlmClient;
import io.github.bassemzohdy.openworkflow.agentic.runtime.agent.GovernedLlmClient;
import io.github.bassemzohdy.openworkflow.agentic.runtime.agent.ApprovedModelContext;
import io.github.bassemzohdy.openworkflow.agentic.runtime.agent.ModelCallRefused;
import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.UlidGenerator;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.*;
import io.github.bassemzohdy.openworkflow.agentic.runtime.policy.Pep;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.net.InetSocketAddress;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Audit of the real provider client: how many HTTP requests one failing {@code complete} makes with the shipped
 * configuration. One allowed PEP decision must admit exactly one physical request; a retry inside the client would be
 * a physical provider call the PEP never decided.
 */
@QuarkusTest
@TestProfile(ProviderRetryAuditTest.Profile.class)
class ProviderRetryAuditTest {

    // The profile and the test are loaded by different class loaders, so the server's port and request count are
    // shared as plain string system properties (a non-string value in the system properties breaks other libraries).
    static final String PORT = "oa.test.retry-audit.port";
    static final String COUNT = "oa.test.retry-audit.count";

    static synchronized int port() {
        synchronized (System.class) {
            if (System.getProperty(PORT) == null) {
                try {
                    HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                    s.createContext("/", exchange -> {
                        synchronized (System.class) {
                            System.setProperty(COUNT, Integer.toString(count() + 1));
                        }
                        exchange.getRequestBody().readAllBytes();
                        byte[] body = "{\"error\":{\"message\":\"boom\"}}".getBytes();
                        exchange.getResponseHeaders().add("Content-Type", "application/json");
                        exchange.sendResponseHeaders(500, body.length);
                        exchange.getResponseBody().write(body);
                        exchange.close();
                    });
                    s.start();
                    System.setProperty(COUNT, "0");
                    System.setProperty(PORT, Integer.toString(s.getAddress().getPort()));
                } catch (java.io.IOException e) {
                    throw new IllegalStateException(e);
                }
            }
            return Integer.parseInt(System.getProperty(PORT));
        }
    }

    static int count() {
        return Integer.parseInt(System.getProperty(COUNT, "0"));
    }

    public static class Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("quarkus.langchain4j.openai.base-url", "http://127.0.0.1:" + port() + "/v1/");
        }
    }

    @Inject
    LangChain4jLlmClient provider;

    @Test
    void anApprovedDifferentModelMakesNoHttpRequest() {
        synchronized (System.class) { System.setProperty(COUNT, "0"); }
        var user = new Subject(SubjectKind.USER, "provider-model-test");
        var agent = new Subject(SubjectKind.AGENT, "provider-model-agent");
        var task = new AgentTaskContract(UlidGenerator.shared().next(), "model identity test", Set.of(), Set.of(),
                RiskTier.LOW, new ExecutionLimits(2, Duration.ofMinutes(1), 1000), user, UlidGenerator.shared().next());
        String actual = provider.configuredModelId();
        org.junit.jupiter.api.Assertions.assertNotNull(actual);
        ApprovedModelContext approved = () -> Optional.of(new ApprovedModelContext.Approved(agent,task,actual + "-different",1000));
        var clock = Clock.systemUTC();
        var pep = new Pep(request -> new Decision("provider-test",DecisionOutcome.ALLOW,"allow",
                new PolicyRef("provider.test","1"),clock.instant()), event -> {}, Duration.ofSeconds(5),clock);

        assertThrows(ModelCallRefused.class, () -> new GovernedLlmClient(provider,pep,approved).complete("system","hello"));
        assertEquals(0,count(),"a mismatched provider must not receive the prompt");
    }

    @Test
    void oneFailingChatMakesExactlyOnePhysicalRequest() {
        synchronized (System.class) {
            System.setProperty(COUNT, "0");
        }
        assertThrows(RuntimeException.class, () -> provider.complete("system", "hello"));
        assertEquals(1, count(), "physical requests made by one chat() against a failing provider");
    }
}
