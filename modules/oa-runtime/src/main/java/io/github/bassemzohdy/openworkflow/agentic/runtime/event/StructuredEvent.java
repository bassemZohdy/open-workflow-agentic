package io.github.bassemzohdy.openworkflow.agentic.runtime.event;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.cloudevents.CloudEvent;
import io.cloudevents.jackson.JsonFormat;
import io.github.bassemzohdy.openworkflow.agentic.contracts.common.CanonicalEncoder;
import io.github.bassemzohdy.openworkflow.agentic.runtime.api.problem.ProblemException;
import java.io.IOException;
import java.io.InputStream;
import java.util.TreeSet;

/** Bounded structured CloudEvents JSON; preserves the complete envelope, including optional/binary data. */
public final class StructuredEvent {
    private final String id, source, type, canonicalEnvelope;
    private StructuredEvent(String id, String source, String type, String canonicalEnvelope) {
        this.id = id; this.source = source; this.type = type; this.canonicalEnvelope = canonicalEnvelope;
    }
    public String id() { return id; }
    public String source() { return source; }
    public String type() { return type; }
    public String canonicalEnvelope() { return canonicalEnvelope; }
    public static final String CONTENT_TYPE = "application/cloudevents+json";
    public static final int MAX_BYTES = 64 * 1024;
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(64)
                    .maxStringLength(MAX_BYTES).maxNumberLength(128).build()).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    public static StructuredEvent read(InputStream input) {
        try {
            byte[] bytes = input.readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) throw new ProblemException(413, "event envelope exceeds 64 KiB");
            JsonNode root = JSON.readTree(bytes);
            if (root == null || !root.isObject() || !root.path("specversion").isTextual()
                    || !"1.0".equals(root.path("specversion").asText())) {
                throw new IllegalArgumentException("structured CloudEvents 1.0 object required");
            }
            byte[] canonical = JSON.writeValueAsBytes(sorted(root));
            if (canonical.length > MAX_BYTES) throw new ProblemException(413, "event envelope exceeds 64 KiB");
            CloudEvent event = new JsonFormat().deserialize(canonical);
            return new StructuredEvent(event.getId(), event.getSource().toString(), event.getType(),
                    new String(canonical, java.nio.charset.StandardCharsets.UTF_8));
        } catch (ProblemException refusal) { throw refusal;
        } catch (IOException | RuntimeException invalid) {
            throw new ProblemException(400, "invalid structured CloudEvent");
        }
    }

    private static JsonNode sorted(JsonNode node) {
        if (node.isObject()) {
            ObjectNode object = JSON.createObjectNode();
            TreeSet<String> names = new TreeSet<>(); node.fieldNames().forEachRemaining(names::add);
            for (String name : names) { CanonicalEncoder.strictUtf8(name); object.set(name, sorted(node.get(name))); }
            return object;
        }
        if (node.isArray()) {
            ArrayNode array = JSON.createArrayNode(); node.forEach(child -> array.add(sorted(child))); return array;
        }
        if (node.isTextual()) CanonicalEncoder.strictUtf8(node.textValue());
        return node;
    }
}
