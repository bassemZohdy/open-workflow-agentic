package io.github.bassemzohdy.openworkflow.agentic.contracts.port;

import io.github.bassemzohdy.openworkflow.agentic.contracts.common.Checks;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid;
import java.util.Objects;

/**
 * A request to call one tool on behalf of an agent task.
 *
 * @param taskId        the agent task making the call
 * @param toolName      the tool, which must be in the task's allow-list
 * @param argumentsJson the arguments as a JSON object, at most {@link #MAX_ARGUMENTS_LENGTH} characters
 */
public record ToolCall(Ulid taskId, String toolName, String argumentsJson) {

    public static final int MAX_ARGUMENTS_LENGTH = 65_536;

    public ToolCall {
        Objects.requireNonNull(taskId, "taskId");
        toolName = Checks.requireText("toolName", toolName, 128);
        argumentsJson = Checks.requireText("argumentsJson", argumentsJson, MAX_ARGUMENTS_LENGTH);
    }
}
