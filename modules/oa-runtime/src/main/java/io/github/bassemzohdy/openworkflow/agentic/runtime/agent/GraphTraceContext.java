package io.github.bassemzohdy.openworkflow.agentic.runtime.agent;

import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import java.util.Objects;
import org.bsc.langgraph4j.action.NodeAction;
import org.bsc.langgraph4j.state.AgentState;

/** Carries OpenTelemetry context across the graph's node executor; it is not an authority source. */
final class GraphTraceContext {
    private GraphTraceContext() { }

    static <S extends AgentState> NodeAction<S> wrap(Context parent, NodeAction<S> action) {
        Objects.requireNonNull(parent); Objects.requireNonNull(action);
        return state -> {
            Scope scope;
            try { scope = parent.makeCurrent(); }
            catch (RuntimeException unavailable) { return action.apply(state); }
            try { return action.apply(state); }
            finally { try { scope.close(); } catch (RuntimeException ignored) { } }
        };
    }
}
