package io.github.bassemzohdy.openworkflow.agentic.contracts.port;

import io.github.bassemzohdy.openworkflow.agentic.contracts.common.Checks;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid;
import java.util.Objects;

/**
 * A request to one model on behalf of an agent task.
 *
 * @param taskId          the agent task making the call
 * @param modelId         the model, as bound in the registry (for example {@code small-fast})
 * @param prompt          the input text, at most {@link #MAX_PROMPT_LENGTH} characters; large documents are referenced
 *                        by id instead (ADR 0020, decision 5)
 * @param maxOutputTokens upper bound on the response, 1 to {@link #MAX_OUTPUT_TOKENS}
 */
public record ModelCall(Ulid taskId, String modelId, String prompt, int maxOutputTokens) {

    public static final int MAX_PROMPT_LENGTH = 65_536;
    public static final int MAX_OUTPUT_TOKENS = 32_768;

    public ModelCall {
        Objects.requireNonNull(taskId, "taskId");
        modelId = Checks.requireText("modelId", modelId, 128);
        prompt = Checks.requireText("prompt", prompt, MAX_PROMPT_LENGTH);
        Checks.requireInRange("maxOutputTokens", maxOutputTokens, 1, MAX_OUTPUT_TOKENS);
    }
}
