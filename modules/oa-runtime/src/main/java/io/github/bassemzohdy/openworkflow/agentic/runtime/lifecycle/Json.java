package io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.github.bassemzohdy.openworkflow.agentic.contracts.common.CanonicalEncoder;
import java.util.Map;
import java.util.Objects;

/**
 * JSON helpers for the registry: the stored form of a workflow input and the fingerprint of a start request.
 * Map keys are written in sorted order, so the same input always gives the same text and the same fingerprint.
 */
final class Json {

    static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    private static final String FINGERPRINT_DOMAIN = "oa.instance.start.v1";

    private Json() {
    }

    /** The canonical JSON text of a workflow input. */
    static String canonical(Map<String, Object> input) {
        Objects.requireNonNull(input, "input");
        try {
            return MAPPER.writeValueAsString(input);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("input is not representable as JSON", e);
        }
    }

    /** Reads a stored input back. */
    static Map<String, Object> parse(String text) {
        try {
            return MAPPER.readValue(text, MAP);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("stored input is not valid JSON", e);
        }
    }

    /** SHA-256 over the definition and the canonical input. Two requests with equal fingerprints are the same request. */
    static String fingerprint(DefinitionRef definition, String canonicalInput) {
        byte[] bytes = new CanonicalEncoder(FINGERPRINT_DOMAIN)
                .text(definition.definitionId())
                .text(definition.version())
                .text(canonicalInput)
                .toBytes();
        return CanonicalEncoder.sha256Hex(bytes);
    }
}
