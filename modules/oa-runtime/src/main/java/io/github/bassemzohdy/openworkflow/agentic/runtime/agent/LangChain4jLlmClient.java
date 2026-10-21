package io.github.bassemzohdy.openworkflow.agentic.runtime.agent;

import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Duration;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Default provider: an OpenAI-compatible chat model, configured with the same properties as the Quarkus extension.
 *
 * <p>The model is built here, with {@code maxRetries(0)}, and not injected from the extension: the extension rejects
 * {@code quarkus.langchain4j.openai.max-retries=0} and its default makes a second HTTP request after a failure (measured
 * by {@code ProviderRetryAuditTest}). The PEP decides one call at a time, so one decision must reach the provider
 * exactly once; a retry inside the client would be a physical call nobody decided or audited.
 */
@ApplicationScoped
public class LangChain4jLlmClient implements LlmClient {

    @ConfigProperty(name = "quarkus.langchain4j.openai.base-url")
    String baseUrl;

    @ConfigProperty(name = "quarkus.langchain4j.openai.api-key")
    String apiKey;

    @ConfigProperty(name = "quarkus.langchain4j.openai.chat-model.model-name")
    String modelName;

    @ConfigProperty(name = "quarkus.langchain4j.timeout", defaultValue = "60s")
    Duration timeout;

    private ChatModel model;

    @PostConstruct
    void build() {
        this.model = OpenAiChatModel.builder()
                .baseUrl(baseUrl).apiKey(apiKey).modelName(modelName)
                .timeout(timeout).maxRetries(0)
                .build();
    }

    @Override
    public String complete(String system, String user) {
        return model.chat(SystemMessage.from(system), UserMessage.from(user)).aiMessage().text();
    }
}
