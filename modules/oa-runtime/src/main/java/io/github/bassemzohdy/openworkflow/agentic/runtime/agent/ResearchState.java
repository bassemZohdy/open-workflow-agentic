package io.github.bassemzohdy.openworkflow.agentic.runtime.agent;

import java.util.Map;
import java.util.Optional;
import org.bsc.langgraph4j.state.AgentState;

/** Shared state of the research agent graph. */
public class ResearchState extends AgentState {

    public static final String TOPIC = "topic";
    public static final String PLAN = "plan";
    public static final String DRAFT = "draft";
    public static final String REVIEW = "review";

    public ResearchState(Map<String, Object> init) {
        super(init);
    }

    public Optional<String> text(String key) {
        return value(key).map(Object::toString);
    }
}
