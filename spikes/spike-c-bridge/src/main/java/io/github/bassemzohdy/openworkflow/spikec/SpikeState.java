package io.github.bassemzohdy.openworkflow.spikec;

import java.util.Map;
import org.bsc.langgraph4j.state.AgentState;

/** Graph state. The context is stored as one JSON string, so the checkpoint carries exactly what the agent received. */
public class SpikeState extends AgentState {

    public static final String CTX = "ctx";
    public static final String DRAFT = "draft";
    public static final String PUBLISHED = "published";

    public SpikeState(Map<String, Object> init) {
        super(init);
    }

    public String text(String key) {
        return value(key).map(Object::toString).orElse("");
    }
}
