package io.github.bassemzohdy.openworkflow.spikec;

import org.bsc.langgraph4j.serializer.plain_text.jackson.JacksonStateSerializer;

/** Plain-text (JSON) state serializer, as ADR 0020 decision 3 requires. Same class as WP-004 spike-b. */
public class JsonSpikeSerializer extends JacksonStateSerializer<SpikeState> {

    public JsonSpikeSerializer() {
        super(SpikeState::new);
    }
}
