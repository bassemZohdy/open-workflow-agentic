package io.github.bassemzohdy.openworkflow.agentic.contracts.agent;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The three standalone fingerprint controls isolated from the existing execution-contract preparation. */
class ResearchOutputFingerprintTest {
    static final String MEDIA=ResearchOutputFingerprint.CONTENT_TYPE, STATE="AAEC/w==";

    @Test void exactStoredRepresentationMatchesIndependentPythonGolden() {
        // Independent struct '>i' + strict UTF-8 + hashlib; decoded stream bytes are a DIFFERENT recipe.
        assertEquals("19fb131295937502013de1e7a8ec0ce6848aba209bd492c85f3faadca8c4d81f",ResearchOutputFingerprint.of(MEDIA,STATE));
        assertNotEquals(ResearchOutputFingerprint.of(MEDIA,STATE),ResearchOutputFingerprint.of(MEDIA,"AAEC/g=="));
        assertEquals(STATE,Base64.getEncoder().encodeToString(new byte[]{0,1,2,(byte)255}));
        // Fingerprinting does not interpret arbitrary bytes as a valid Java object or completed graph.
    }
    @Test void unsupportedAndAmbiguousStoredEncodingsRefuseWithoutNormalization() {
        for(var invalid:List.of("","YQ","YQ=","YR==","YQ==\n","____","é===","\uD800"))
            assertThrows(IllegalArgumentException.class,()->ResearchOutputFingerprint.of(MEDIA,invalid));
        assertThrows(IllegalArgumentException.class,()->ResearchOutputFingerprint.of("application/json",STATE));
        assertThrows(IllegalArgumentException.class,()->ResearchOutputFingerprint.of(MEDIA+" ",STATE));
        assertThrows(NullPointerException.class,()->ResearchOutputFingerprint.of(MEDIA,null));
        assertThrows(NullPointerException.class,()->ResearchOutputFingerprint.of(null,STATE));
    }
    @Test void encodedStateIsBoundedBeforeDecodeOrHash() {
        String oversized="A".repeat(ResearchOutputFingerprint.MAX_ENCODED_BYTES+4);
        assertThrows(IllegalArgumentException.class,()->ResearchOutputFingerprint.of(MEDIA,oversized));
    }
}
