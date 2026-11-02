package io.github.bassemzohdy.openworkflow.agentic.runtime.agent;

import static org.bsc.langgraph4j.StateGraph.END;
import static org.bsc.langgraph4j.StateGraph.START;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;
import static org.junit.jupiter.api.Assertions.*;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.serializer.std.ObjectStreamStateSerializer;
import org.junit.jupiter.api.Test;

class GraphTraceContextTest {
    @Test void graphNodeKeepsTheParentTraceAcrossTheExecutorAndRestoresWorkerContext() throws Exception {
        var exporter = InMemorySpanExporter.create();
        try (var sdk = SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build();
             var worker = Executors.newSingleThreadExecutor()) {
            var parent = sdk.get("caller").spanBuilder("parent").startSpan();
            var model = new ModelCallTelemetry(sdk.get("model"));
            Context captured;
            try (var ignored = parent.makeCurrent()) { captured = Context.current(); }
            var node = GraphTraceContext.<ResearchState>wrap(captured, state -> {
                assertEquals(parent.getSpanContext(), Span.current().getSpanContext());
                return Map.of("answer", model.call(() -> "result"));
            });
            var graph = new StateGraph<>(new ObjectStreamStateSerializer<>(ResearchState::new))
                    .addNode("node", node_async(node)).addEdge(START, "node").addEdge("node", END).compile();
            try {
                var output = worker.submit(() -> {
                    var result = graph.invoke(Map.of()).orElseThrow();
                    assertFalse(Span.current().getSpanContext().isValid(), "worker context must be restored");
                    return result;
                }).get(5, TimeUnit.SECONDS);
                assertEquals("result", output.value("answer").orElseThrow());
            } finally { parent.end(); }
            var span = exporter.getFinishedSpanItems().stream().filter(s -> s.getName().equals("oa.model.call")).findFirst().orElseThrow();
            assertEquals(parent.getSpanContext().getSpanId(), span.getParentSpanId());
            assertEquals(parent.getSpanContext().getTraceId(), span.getTraceId());
        }
    }
}
