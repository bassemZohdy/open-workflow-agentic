package spikeb;

import org.bsc.langgraph4j.serializer.plain_text.jackson.JacksonStateSerializer;

/** Plain-text (JSON) state serializer, for comparison with the Java-stream serializer. */
public class JsonSpikeSerializer extends JacksonStateSerializer<SpikeState> {

    public JsonSpikeSerializer() {
        super(SpikeState::new);
    }
}
