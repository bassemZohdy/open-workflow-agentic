package io.github.bassemzohdy.openworkflow.agentic.runtime.identity;

import static io.github.bassemzohdy.openworkflow.agentic.runtime.identity.IdentityTestSupport.MovableClock;
import static io.github.bassemzohdy.openworkflow.agentic.runtime.identity.IdentityTestSupport.RecordingSink;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.AuditEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Reload, rotation and failure handling of {@link FileSecretProvider}. No database; a real directory and a fake clock. */
class FileSecretProviderTest {

    private static final Duration TTL = Duration.ofSeconds(30);
    private static final Duration STALE = Duration.ofMinutes(2);

    @TempDir
    Path dir;

    private MovableClock clock;
    private RecordingSink sink;
    private FileSecretProvider secrets;
    private long mtime;

    @BeforeEach
    void start() {
        clock = new MovableClock(Instant.parse("2026-10-19T10:00:00Z"));
        sink = new RecordingSink();
        secrets = new FileSecretProvider(dir, clock, TTL, STALE, IdentityTestSupport.audit(sink, clock));
    }

    private void write(String name, String content) throws IOException {
        Path file = dir.resolve(name);
        Files.writeString(file, content);
        Files.setLastModifiedTime(file, FileTime.fromMillis(1_700_000_000_000L + (++mtime) * 1000));
    }

    private String value(String name) {
        return secrets.current(name).reveal();
    }

    private void assertUnavailable(String name) {
        SecretUnavailableException e = assertThrows(SecretUnavailableException.class, () -> secrets.current(name));
        assertFalse(String.valueOf(e.getMessage()).contains("hunter2"), "no value in the message");
    }

    // ---- reading ---------------------------------------------------------------------------------------------------

    @Test
    void readsTheFileAndRemovesOneTrailingLineBreak() throws IOException {
        write("a", "hunter2\n");
        write("b", "hunter2\r\n");
        write("c", "hunter2\n\n");
        write("d", "line1\nline2");

        assertEquals("hunter2", value("a"));
        assertEquals("hunter2", value("b"));
        assertEquals("hunter2\n", value("c"), "only one line break is removed");
        assertEquals("line1\nline2", value("d"));
    }

    @Test
    void theValueNeverShowsInTheCredentialsTextOrInAnyAuditEvent() throws IOException {
        write("a", "hunter2");

        Credential credential = secrets.current("a");
        write("a", "hunter3");
        secrets.current("a");

        assertEquals("[REDACTED]", credential.toString());
        assertFalse(sink.events.toString().contains("hunter"));
    }

    // ---- reload and rotation -------------------------------------------------------------------------------------------

    @Test
    void aChangedFileIsReadAtOnceAndTheRotationIsAuditedWithNoValue() throws IOException {
        write("a", "hunter2");
        assertEquals("hunter2", value("a"));
        assertEquals(1, secrets.generation("a"));
        assertTrue(sink.byAction(FileSecretProvider.ROTATED).isEmpty(), "the first load is not a rotation");

        write("a", "hunter3");

        assertEquals("hunter3", value("a"), "no waiting for the ttl");
        assertEquals(2, secrets.generation("a"));
        AuditEvent event = sink.byAction(FileSecretProvider.ROTATED).get(0);
        assertEquals("a", event.resourceId());
        assertEquals("2", event.attributes().get("generation"));
        assertFalse(event.toString().contains("hunter"));
    }

    @Test
    void anUnchangedFileIsReadAgainOnlyAfterTheTtl() throws IOException {
        write("a", "hunter2");
        assertEquals("hunter2", value("a"));
        Path file = dir.resolve("a");
        FileTime before = Files.getLastModifiedTime(file);
        Files.writeString(file, "hunter9");          // same size, same modification time: invisible to the check
        Files.setLastModifiedTime(file, before);

        assertEquals("hunter2", value("a"), "within the ttl the cached value is trusted");
        clock.advance(TTL.plusSeconds(1));
        assertEquals("hunter9", value("a"), "after the ttl the file is read again");
    }

    @Test
    void aSecretMountedTheKubernetesWayThroughSymlinksInsideTheDirectoryWorks() throws IOException {
        Path data = Files.createDirectory(dir.resolve("..2026_10_19"));
        Files.writeString(data.resolve("k8s"), "mounted");
        Files.createSymbolicLink(dir.resolve("..data"), Path.of("..2026_10_19"));
        Files.createSymbolicLink(dir.resolve("k8s"), Path.of("..data/k8s"));

        assertEquals("mounted", value("k8s"));

        Path next = Files.createDirectory(dir.resolve("..2026_10_20"));
        Files.writeString(next.resolve("k8s"), "rotated");
        Files.delete(dir.resolve("..data"));
        Files.createSymbolicLink(dir.resolve("..data"), Path.of("..2026_10_20"));

        assertEquals("rotated", value("k8s"), "the atomic swap of the mount is a rotation");
    }

    // ---- failures ------------------------------------------------------------------------------------------------------

    @Test
    void aDeletedFileRevokesTheSecretAtOnceEvenWithinTheTtl() throws IOException {
        write("a", "hunter2");
        assertEquals("hunter2", value("a"));

        Files.delete(dir.resolve("a"));

        assertUnavailable("a");
        assertUnavailable("a");
        assertEquals(1, sink.byAction(FileSecretProvider.UNAVAILABLE).size(), "an outage is audited once, not per call");
        assertEquals("missing", sink.byAction(FileSecretProvider.UNAVAILABLE).get(0).attributes().get("reason"));

        write("a", "hunter2");
        assertEquals("hunter2", value("a"), "the secret is back");
        assertEquals(1, sink.byAction(FileSecretProvider.ROTATED).size(), "coming back is recorded");
    }

    @Test
    void aReadErrorUsesTheCachedValueOnlyForTheBoundedGraceThenFails() throws IOException {
        write("a", "hunter2");
        assertEquals("hunter2", value("a"));
        Files.delete(dir.resolve("a"));
        Files.createDirectory(dir.resolve("a"));       // the path exists but cannot be read as a secret: an I/O error

        assertEquals("hunter2", value("a"), "a transient error does not stop work at once");
        clock.advance(TTL.plus(STALE).minusSeconds(1));
        assertEquals("hunter2", value("a"), "still inside the grace");
        clock.advance(Duration.ofSeconds(2));
        assertUnavailable("a");
        assertEquals("unreadable", sink.byAction(FileSecretProvider.UNAVAILABLE).get(0).attributes().get("reason"));

        Files.delete(dir.resolve("a"));
        write("a", "hunter4");
        assertEquals("hunter4", value("a"), "recovery needs no restart");
    }

    @Test
    void aValueThatWasNeverReadIsNotInventedDuringAnError() throws IOException {
        Files.createDirectory(dir.resolve("a"));

        assertUnavailable("a");
    }

    @Test
    void anEmptyFileIsUnavailableAndDoesNotFallBackToTheOlderValue() throws IOException {
        write("a", "hunter2");
        assertEquals("hunter2", value("a"));

        write("a", "  \n");

        assertUnavailable("a");
        write("a", "");
        assertUnavailable("a");
    }

    @Test
    void aFileThatIsTooLargeOrNotTextIsUnavailable() throws IOException {
        byte[] big = new byte[FileSecretProvider.MAX_BYTES + 1];
        Arrays.fill(big, (byte) 'x');
        Files.write(dir.resolve("big"), big);
        Files.write(dir.resolve("binary"), new byte[] {(byte) 0xC3, (byte) 0x28, 'h', 'u', 'n', 't', 'e', 'r', '2'});

        assertUnavailable("big");
        assertUnavailable("binary");
    }

    // ---- names and paths -----------------------------------------------------------------------------------------------

    @Test
    void onlyPlainFileNamesAreAccepted() throws IOException {
        write("ok", "hunter2");
        Files.writeString(dir.resolve("..hidden"), "hunter2");

        for (String bad : new String[] {null, "", " ", "../ok", "a/b", "/etc/passwd", ".hidden", "..hidden", "a\0b",
                "a b", "x".repeat(129)}) {
            assertThrows(SecretUnavailableException.class, () -> secrets.current(bad), "name: " + bad);
        }
        assertEquals("hunter2", value("ok"));
    }

    @Test
    void aSymlinkThatLeavesTheDirectoryIsRefused(@TempDir Path outside) throws IOException {
        Files.writeString(outside.resolve("target"), "hunter2");
        Files.createSymbolicLink(dir.resolve("escape"), outside.resolve("target"));

        assertUnavailable("escape");
    }

    @Test
    void aMissingDirectoryIsRejectedAtConstruction() {
        assertThrows(IllegalArgumentException.class, () -> new FileSecretProvider(dir.resolve("nope"), clock, TTL, STALE,
                null));
        assertThrows(IllegalArgumentException.class, () -> new FileSecretProvider(dir, clock, Duration.ZERO, STALE, null));
    }

    // ---- audit is not on the critical path --------------------------------------------------------------------------

    @Test
    void aFailingAuditTrailDoesNotStopTheSecretFromBeingRead() throws IOException {
        write("a", "hunter2");
        value("a");
        sink.failing = true;

        write("a", "hunter3");

        assertEquals("hunter3", value("a"));
    }
}
