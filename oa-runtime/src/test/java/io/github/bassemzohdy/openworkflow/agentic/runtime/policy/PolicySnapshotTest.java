package io.github.bassemzohdy.openworkflow.agentic.runtime.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;

class PolicySnapshotTest {

    /** The hash layout as documented: length-prefixed domain, version, count, then length-prefixed texts. */
    private static String expectedHash(String version, List<String> texts) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        writeText(out, PolicySnapshot.HASH_DOMAIN);
        writeText(out, version);
        out.writeLong(texts.size());
        for (String text : texts) {
            writeText(out, text);
        }
        out.flush();
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void writeText(DataOutputStream out, String text) throws IOException {
        byte[] utf8 = text.getBytes(StandardCharsets.UTF_8);
        out.writeInt(utf8.length);
        out.write(utf8);
    }

    @Test
    void theHashFollowsTheDocumentedLayout() throws IOException {
        List<String> texts = List.of("permit (principal, action, resource);", "");
        assertEquals(expectedHash("2026-10-18.1", texts), PolicySnapshot.hashOf("2026-10-18.1", texts));
    }

    @Test
    void theHashCoversTheVersionOrderAndEveryText() {
        String base = PolicySnapshot.hashOf("v1", List.of("a", "b"));
        assertNotEquals(base, PolicySnapshot.hashOf("v2", List.of("a", "b")));
        assertNotEquals(base, PolicySnapshot.hashOf("v1", List.of("b", "a")));
        assertNotEquals(base, PolicySnapshot.hashOf("v1", List.of("a", "b ")));
        assertNotEquals(base, PolicySnapshot.hashOf("v1", List.of("a")));
        // Length prefixes keep "ab"+"c" apart from "a"+"bc".
        assertNotEquals(PolicySnapshot.hashOf("v1", List.of("ab", "c")),
                PolicySnapshot.hashOf("v1", List.of("a", "bc")));
    }

    @Test
    void createComputesAHashThatVerifies() {
        PolicySnapshot snapshot = PolicySnapshot.create("v1", List.of("x"));
        assertEquals(PolicySnapshot.hashOf("v1", List.of("x")), snapshot.contentHash());
    }

    @Test
    void aMismatchedHashCannotBeConstructed() {
        assertThrows(IllegalArgumentException.class,
                () -> new PolicySnapshot("v1", "f".repeat(64), List.of("x")));
    }

    @Test
    void theHashMustBeLowerCaseHex() {
        String upper = PolicySnapshot.hashOf("v1", List.of("x")).toUpperCase(java.util.Locale.ROOT);
        assertThrows(IllegalArgumentException.class, () -> new PolicySnapshot("v1", upper, List.of("x")));
    }

    @Test
    void versionIdsAreRestrictedToASafeFormat() {
        for (String bad : List.of("", " v1", "v 1", "../v1", "v1/x", "-v1", "v".repeat(65))) {
            assertThrows(IllegalArgumentException.class, () -> PolicySnapshot.create(bad, List.of()), bad);
        }
        assertEquals("2026-10-18.1", PolicySnapshot.create("2026-10-18.1", List.of()).versionId());
    }

    @Test
    void anEmptySetOfTextsIsAValidSnapshot() {
        PolicySnapshot empty = PolicySnapshot.create("v0", List.of());
        assertEquals(List.of(), empty.policyTexts());
    }

    @Test
    void aPolicyTextLongerThanTheLimitIsRefused() {
        String tooLong = "x".repeat(PolicySnapshot.MAX_POLICY_TEXT_LENGTH + 1);
        assertThrows(IllegalArgumentException.class, () -> PolicySnapshot.create("v1", List.of(tooLong)));
    }

    @Test
    void theTextsAreAnImmutableCopy() {
        java.util.ArrayList<String> texts = new java.util.ArrayList<>(List.of("x"));
        PolicySnapshot snapshot = PolicySnapshot.create("v1", texts);
        texts.add("y");
        assertEquals(List.of("x"), snapshot.policyTexts());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.policyTexts().add("z"));
    }
}
