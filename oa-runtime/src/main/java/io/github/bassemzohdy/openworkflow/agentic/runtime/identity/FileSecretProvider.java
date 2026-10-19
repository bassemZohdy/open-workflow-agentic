package io.github.bassemzohdy.openworkflow.agentic.runtime.identity;

import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.Subject;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.SubjectKind;
import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import org.jboss.logging.Logger;

/**
 * Secrets from files in one directory, for example a Kubernetes Secret mounted as a volume. A rotation is a new file
 * content; no restart is needed.
 *
 * <ul>
 *   <li><b>Where:</b> one directory. A secret name is a plain file name ({@code [A-Za-z0-9][A-Za-z0-9._-]{0,127}}); the
 *       file must resolve inside the directory (a symlink out of it is refused; the symlinks Kubernetes uses inside the
 *       mount are fine).</li>
 *   <li><b>Reload:</b> every call looks at the file's identity (real path, modification time, size). A change is read at
 *       once. An unchanged file is re-read at least every {@code ttl}.</li>
 *   <li><b>Missing file:</b> the secret is gone now. The cached value is dropped and the call fails. Removing a file
 *       revokes the secret.</li>
 *   <li><b>Read error (not "missing"):</b> a cached value that is younger than {@code ttl + maxStale} is still used, and
 *       the failure is logged without any value. Beyond that the call fails. No value is used forever: a failing disk
 *       pauses dependent work after at most {@code ttl + maxStale} (default 30 s + 2 min).</li>
 *   <li><b>Content:</b> UTF-8, at most 64 KiB, one trailing line break removed, not blank. Anything else is unavailable.</li>
 *   <li><b>Never:</b> a value is never logged, never put in an exception message, and never in an audit attribute. The
 *       audit events carry the secret's name and a generation number only (no hash, which would let a leaked audit log be
 *       tested against guesses).</li>
 * </ul>
 *
 * Rotating values must not be in environment variables (they cannot change in a running process and show in process
 * listings); this class is the supported way. Vault is out of scope.
 */
public final class FileSecretProvider implements SecretProvider {

    public static final String ROTATED = "identity.secret.rotated";
    public static final String UNAVAILABLE = "identity.secret.unavailable";

    static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");
    static final int MAX_BYTES = 64 * 1024;

    private static final Logger LOG = Logger.getLogger(FileSecretProvider.class);
    private static final Subject WHO = new Subject(SubjectKind.SERVICE, "oa-runtime");

    private record FileKey(Path realPath, FileTime modified, long size) {
    }

    private static final class Entry {
        final Credential value;
        final FileKey key;
        final Instant loadedAt;
        final long generation;

        Entry(Credential value, FileKey key, Instant loadedAt, long generation) {
            this.value = value;
            this.key = key;
            this.loadedAt = loadedAt;
            this.generation = generation;
        }
    }

    private final Path directory;
    private final Clock clock;
    private final Duration ttl;
    private final Duration maxStale;
    private final IdentityAudit audit;
    private final Map<String, Entry> cache = new HashMap<>();
    private final Map<String, Long> generations = new HashMap<>();
    private final Map<String, Boolean> unavailable = new HashMap<>();

    /**
     * @param directory the directory that holds the secret files; must exist
     * @param ttl       how long an unchanged file is trusted before it is read again (positive)
     * @param maxStale  how long after {@code ttl} a cached value may still be used while the file cannot be read (zero
     *                  or more)
     * @param audit     where rotations and outages are recorded (names and generations only); null for none
     */
    public FileSecretProvider(Path directory, Clock clock, Duration ttl, Duration maxStale, IdentityAudit audit) {
        Objects.requireNonNull(directory, "directory");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.ttl = Objects.requireNonNull(ttl, "ttl");
        this.maxStale = Objects.requireNonNull(maxStale, "maxStale");
        if (ttl.isZero() || ttl.isNegative() || maxStale.isNegative()) {
            throw new IllegalArgumentException("ttl must be positive and maxStale must not be negative");
        }
        try {
            this.directory = directory.toRealPath();
        } catch (IOException e) {
            throw new IllegalArgumentException("the secrets directory does not exist or cannot be read: " + directory, e);
        }
        if (!Files.isDirectory(this.directory)) {
            throw new IllegalArgumentException("the secrets path is not a directory: " + directory);
        }
        this.audit = audit;
    }

    @Override
    public synchronized Credential current(String name) {
        if (name == null || !NAME.matcher(name).matches()) {
            throw new SecretUnavailableException("not a valid secret name");
        }
        Instant now = clock.instant();
        Entry cached = cache.get(name);
        Path file = directory.resolve(name);
        try {
            FileKey key = keyOf(file);
            if (cached != null && cached.key.equals(key) && now.isBefore(cached.loadedAt.plus(ttl))) {
                return cached.value;
            }
            return load(name, file, key, cached, now);
        } catch (NoSuchFileException gone) {
            cache.remove(name);
            markUnavailable(name, "missing", now);
            throw new SecretUnavailableException("secret '" + name + "' does not exist");
        } catch (IOException | RuntimeException failure) {
            return staleOrFail(name, cached, now, failure);
        }
    }

    /** How many different values this provider has loaded for the name (1 for the first). For audit and tests. */
    public synchronized long generation(String name) {
        return generations.getOrDefault(name, 0L);
    }

    /** Forgets the cached value; the next call reads the file. */
    public synchronized void invalidate(String name) {
        cache.remove(name);
    }

    private FileKey keyOf(Path file) throws IOException {
        Path real = file.toRealPath();
        if (!real.startsWith(directory)) {
            throw new SecretUnavailableException("secret file resolves outside the secrets directory");
        }
        if (!Files.isRegularFile(real)) {
            throw new IOException("not a regular file");
        }
        return new FileKey(real, Files.getLastModifiedTime(real), Files.size(real));
    }

    private Credential load(String name, Path file, FileKey key, Entry previous, Instant now) throws IOException {
        if (key.size() > MAX_BYTES) {
            throw new SecretUnavailableException("secret '" + name + "' is larger than " + MAX_BYTES + " bytes");
        }
        byte[] bytes = Files.readAllBytes(key.realPath());
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw new SecretUnavailableException("secret '" + name + "' is not valid UTF-8");
        } finally {
            java.util.Arrays.fill(bytes, (byte) 0);
        }
        if (text.endsWith("\r\n")) {
            text = text.substring(0, text.length() - 2);
        } else if (text.endsWith("\n")) {
            text = text.substring(0, text.length() - 1);
        }
        if (text.isBlank()) {
            throw new SecretUnavailableException("secret '" + name + "' is empty");
        }
        Credential value = Credential.of(text);
        boolean changed = previous == null || !previous.value.reveal().equals(text);
        long generation = generations.getOrDefault(name, 0L);
        if (changed) {
            generation++;
            generations.put(name, generation);
        }
        cache.put(name, new Entry(value, key, now, generation));
        boolean wasDown = Boolean.TRUE.equals(unavailable.remove(name));
        if (changed && (generation > 1 || wasDown)) {
            record(ROTATED, name, now, "generation", Long.toString(generation));
        }
        return value;
    }

    private Credential staleOrFail(String name, Entry cached, Instant now, Exception failure) {
        if (failure instanceof SecretUnavailableException unusable) {
            // the file was read but is not usable (empty, too large, not text): do not fall back to an older value
            cache.remove(name);
            markUnavailable(name, "unusable", now);
            throw unusable;
        }
        if (cached != null && now.isBefore(cached.loadedAt.plus(ttl).plus(maxStale))) {
            LOG.warnf("secret '%s' cannot be read (%s); using the cached value until %s", name,
                    failure.getClass().getSimpleName(), cached.loadedAt.plus(ttl).plus(maxStale));
            return cached.value;
        }
        cache.remove(name);
        markUnavailable(name, "unreadable", now);
        LOG.errorf("secret '%s' cannot be read (%s)", name, failure.getClass().getSimpleName());
        throw new SecretUnavailableException("secret '" + name + "' cannot be read", failure);
    }

    private void markUnavailable(String name, String reason, Instant now) {
        if (unavailable.put(name, Boolean.TRUE) == null) {
            record(UNAVAILABLE, name, now, "reason", reason);
        }
    }

    private void record(String action, String name, Instant now, String key, String value) {
        if (audit == null) {
            return;
        }
        try {
            audit.record(WHO, action, IdentityAudit.SECRET, name, null, null, Map.of(key, value));
        } catch (RuntimeException e) {
            LOG.warnf("the audit event %s for secret '%s' could not be written", action, name);
        }
    }
}
