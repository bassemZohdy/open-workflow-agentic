package io.github.bassemzohdy.openworkflow.agentic.agent;

import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/** Default implementation: the Quarkus LangChain4j chat model (provider chosen by configuration). */
@ApplicationScoped
public class LangChain4jLlmClient implements LlmClient {

    @Inject
    ChatModel model;

    @Override
    public String complete(String system, String user) {
        return model.chat(SystemMessage.from(system), UserMessage.from(user)).aiMessage().text();
    }
}
