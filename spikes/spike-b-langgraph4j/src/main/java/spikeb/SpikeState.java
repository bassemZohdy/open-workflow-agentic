package spikeb;

import java.util.Map;
import org.bsc.langgraph4j.state.AgentState;

/** State of the spike graphs. Keys mirror ResearchState in the application. */
public class SpikeState extends AgentState {

    public SpikeState(Map<String, Object> init) {
        super(init);
    }

    /** Text value of a key, or empty string when absent. */
    public String text(String key) {
        return value(key).map(Object::toString).orElse("");
    }
}
