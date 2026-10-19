package io.github.bassemzohdy.openworkflow.agentic.runtime.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ManifestPolicySnapshotSourceTest {

    @TempDir
    Path dir;

    private void manifest(String body) throws IOException {
        Files.writeString(dir.resolve(ManifestPolicySnapshotSource.MANIFEST), body, StandardCharsets.UTF_8);
    }

    @Test
    void readsTheVersionHashAndTextsInDeclarationOrder() throws IOException {
        PolicyFixtures.writeSnapshot(dir, PolicyFixtures.V1, PolicyFixtures.referenceSet());
        PublishedPolicySnapshot read = ManifestPolicySnapshotSource.ofDirectory(dir).read();
        assertEquals(PolicyFixtures.V1, read.versionId());
        assertEquals(PolicySnapshot.hashOf(PolicyFixtures.V1, PolicyFixtures.referenceSet()),
                read.declaredContentHash());
        assertEquals(PolicyFixtures.referenceSet(), read.policyTexts());
    }

    @Test
    void readsTheSameSnapshotFromTheClasspath() throws Exception {
        Path folder = dir.resolve("classpath-v1");
        PolicyFixtures.writeSnapshot(folder, PolicyFixtures.V1, List.of(PolicyFixtures.MODELS));
        try (URLClassLoader loader = new URLClassLoader(new URL[] {dir.toUri().toURL()}, null)) {
            PublishedPolicySnapshot read = ManifestPolicySnapshotSource.ofClasspath(loader, "classpath-v1").read();
            assertEquals(List.of(PolicyFixtures.MODELS), read.policyTexts());
        }
    }

    @Test
    void aMissingClasspathFolderIsAReadFailure() throws Exception {
        try (URLClassLoader loader = new URLClassLoader(new URL[] {dir.toUri().toURL()}, null)) {
            assertThrows(IOException.class,
                    () -> ManifestPolicySnapshotSource.ofClasspath(loader, "nowhere").read());
        }
    }

    @Test
    void anEmptyPolicyListIsAnEmptySnapshot() throws IOException {
        PolicyFixtures.writeSnapshot(dir, PolicyFixtures.V1, List.of());
        assertEquals(List.of(), ManifestPolicySnapshotSource.ofDirectory(dir).read().policyTexts());
    }

    @Test
    void aMissingKeyIsAReadFailure() throws IOException {
        manifest("version=" + PolicyFixtures.V1 + "\npolicies=\n");
        IOException e = assertThrows(IOException.class, () -> ManifestPolicySnapshotSource.ofDirectory(dir).read());
        assertTrue(e.getMessage().contains("sha256"), e.getMessage());
    }

    @Test
    void aMissingPoliciesKeyIsAReadFailure() throws IOException {
        manifest("version=" + PolicyFixtures.V1 + "\nsha256=" + "a".repeat(64) + "\n");
        assertThrows(IOException.class, () -> ManifestPolicySnapshotSource.ofDirectory(dir).read());
    }

    @Test
    void namesThatLeaveTheFolderAreRefused() throws IOException {
        for (String bad : List.of("../secret.cedar", "sub/x.cedar", "x.txt", "a b.cedar", "/etc/passwd.cedar",
                "..\\x.cedar", ".hidden.cedar")) {
            manifest("version=" + PolicyFixtures.V1 + "\nsha256=" + "a".repeat(64) + "\npolicies=" + bad + "\n");
            IOException e = assertThrows(IOException.class, () -> ManifestPolicySnapshotSource.ofDirectory(dir).read(),
                    bad);
            assertTrue(e.getMessage().contains("not allowed"), e.getMessage());
        }
    }

    @Test
    void aMissingPolicyFileIsAReadFailure() throws IOException {
        manifest("version=" + PolicyFixtures.V1 + "\nsha256=" + "a".repeat(64) + "\npolicies=gone.cedar\n");
        assertThrows(IOException.class, () -> ManifestPolicySnapshotSource.ofDirectory(dir).read());
    }

    @Test
    void aPolicyFileThatIsNotUtf8IsAReadFailure() throws IOException {
        Files.write(dir.resolve("bad.cedar"), new byte[] {(byte) 0xff, (byte) 0xfe, 0x00});
        manifest("version=" + PolicyFixtures.V1 + "\nsha256=" + "a".repeat(64) + "\npolicies=bad.cedar\n");
        IOException e = assertThrows(IOException.class, () -> ManifestPolicySnapshotSource.ofDirectory(dir).read());
        assertTrue(e.getMessage().contains("UTF-8"), e.getMessage());
    }

    @Test
    void anOversizedPolicyFileIsAReadFailure() throws IOException {
        Files.writeString(dir.resolve("big.cedar"), "x".repeat(ManifestPolicySnapshotSource.MAX_POLICY_FILE_BYTES + 1));
        manifest("version=" + PolicyFixtures.V1 + "\nsha256=" + "a".repeat(64) + "\npolicies=big.cedar\n");
        assertThrows(IOException.class, () -> ManifestPolicySnapshotSource.ofDirectory(dir).read());
    }

    @Test
    void anOversizedManifestIsAReadFailure() throws IOException {
        manifest("# " + "x".repeat(ManifestPolicySnapshotSource.MAX_MANIFEST_BYTES) + "\n");
        assertThrows(IOException.class, () -> ManifestPolicySnapshotSource.ofDirectory(dir).read());
    }

    @Test
    void aMissingFolderIsAReadFailure() {
        assertThrows(IOException.class,
                () -> ManifestPolicySnapshotSource.ofDirectory(dir.resolve("not-there")).read());
    }
}
