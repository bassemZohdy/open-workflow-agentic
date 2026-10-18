package io.github.bassemzohdy.openworkflow.spike.protocols.a2a;

import java.util.Map;
import org.a2aproject.sdk.client.Client;
import org.a2aproject.sdk.client.ClientEvent;
import org.a2aproject.sdk.client.TaskEvent;
import org.a2aproject.sdk.client.TaskUpdateEvent;
import org.a2aproject.sdk.client.config.ClientConfig;
import org.a2aproject.sdk.client.transport.jsonrpc.JSONRPCTransport;
import org.a2aproject.sdk.client.transport.jsonrpc.JSONRPCTransportConfigBuilder;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskState;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;

/** Shared helpers: build a JSON-RPC SDK client for the in-process reference server and record its events. */
final class A2ATestSupport {

    private A2ATestSupport() {
    }

    static Client client(AgentCard card, boolean streaming) throws Exception {
        return Client.builder(card)
                .clientConfig(new ClientConfig.Builder().setStreaming(streaming).build())
                .withTransport(JSONRPCTransport.class, new JSONRPCTransportConfigBuilder())
                .build();
    }

    /** Collects task states from every event the client receives, in order. */
    static final class StateRecorder {
        final List<TaskState> states = new CopyOnWriteArrayList<>();
        final List<String> taskIds = new CopyOnWriteArrayList<>();
        final List<String> contextIds = new CopyOnWriteArrayList<>();

        BiConsumer<ClientEvent, AgentCard> consumer() {
            return (event, card) -> {
                Task task = null;
                if (event instanceof TaskEvent taskEvent) {
                    task = taskEvent.getTask();
                } else if (event instanceof TaskUpdateEvent updateEvent) {
                    task = updateEvent.getTask();
                }
                if (task != null) {
                    states.add(task.status().state());
                    taskIds.add(task.id());
                    contextIds.add(task.contextId());
                }
            };
        }

        boolean hasState(TaskState state) {
            return states.contains(state);
        }
    }

    static Map<String, String> noHeaders() {
        return Map.of();
    }
}
