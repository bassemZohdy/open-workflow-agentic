package io.github.bassemzohdy.openworkflow.agentic.runtime.policy;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.regex.Pattern;

/**
 * A {@link PolicySnapshotSource} that reads a manifest and the policy files it names, from a directory or from the
 * classpath. The manifest is a properties file:
 *
 * <pre>
 * version=2026-10-18.1
 * sha256=&lt;64 lower-case hex characters&gt;
 * policies=tool-calls.cedar,operations.cedar
 * </pre>
 *
 * <p>The policy names are file names in the same folder, listed in declaration order. An empty {@code policies} value
 * is a snapshot with no policies, which denies every request. A name with a path separator, a dot-dot segment or any
 * other character outside the file-name pattern is refused, so the manifest cannot read outside its folder. Text is
 * decoded strictly as UTF-8. Any read or format problem throws {@link IOException}; the runtime then keeps its last
 * good snapshot.
 */
public final class ManifestPolicySnapshotSource implements PolicySnapshotSource {

    /** File name of the manifest inside the snapshot folder. */
    public static final String MANIFEST = "snapshot.properties";
    static final Pattern POLICY_FILE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}\\.cedar");
    static final int MAX_MANIFEST_BYTES = 16 * 1024;
    static final int MAX_POLICY_FILE_BYTES = PolicySnapshot.MAX_POLICY_TEXT_LENGTH * 4;

    /** Opens one named resource of the snapshot. Throws {@link IOException} when it is missing or unreadable. */
    @FunctionalInterface
    interface Opener {
        InputStream open(String name) throws IOException;
    }

    private final Opener opener;

    private ManifestPolicySnapshotSource(Opener opener) {
        this.opener = Objects.requireNonNull(opener, "opener");
    }

    /** A snapshot folder on disk, for example {@code /var/lib/oa/policy/current}. */
    public static ManifestPolicySnapshotSource ofDirectory(Path directory) {
        Objects.requireNonNull(directory, "directory");
        return new ManifestPolicySnapshotSource(name -> Files.newInputStream(directory.resolve(name)));
    }

    /** A snapshot folder on the classpath, for example {@code policy/v1}; the folder has no leading slash. */
    public static ManifestPolicySnapshotSource ofClasspath(ClassLoader loader, String folder) {
        Objects.requireNonNull(loader, "loader");
        String base = Objects.requireNonNull(folder, "folder").replaceAll("/+$", "");
        return new ManifestPolicySnapshotSource(name -> {
            InputStream in = loader.getResourceAsStream(base + "/" + name);
            if (in == null) {
                throw new IOException("classpath resource not found: " + base + "/" + name);
            }
            return in;
        });
    }

    @Override
    public PublishedPolicySnapshot read() throws IOException {
        Properties manifest = readManifest();
        String version = required(manifest, "version");
        String sha256 = required(manifest, "sha256");
        List<String> names = policyNames(manifest.getProperty("policies"));
        List<String> texts = new ArrayList<>(names.size());
        for (String name : names) {
            texts.add(decodeStrict(readBounded(name, MAX_POLICY_FILE_BYTES)));
        }
        return new PublishedPolicySnapshot(version, sha256, texts);
    }

    private Properties readManifest() throws IOException {
        String text = decodeStrict(readBounded(MANIFEST, MAX_MANIFEST_BYTES));
        Properties properties = new Properties();
        try {
            properties.load(new StringReader(text));
        } catch (IllegalArgumentException e) {
            throw new IOException("manifest " + MANIFEST + " is malformed", e);
        }
        return properties;
    }

    private byte[] readBounded(String name, int limit) throws IOException {
        try (InputStream in = opener.open(name)) {
            byte[] bytes = in.readNBytes(limit + 1);
            if (bytes.length > limit) {
                throw new IOException(name + " is larger than " + limit + " bytes");
            }
            return bytes;
        }
    }

    /** Strict UTF-8: malformed input is an error, never replacement characters. */
    static String decodeStrict(byte[] bytes) throws IOException {
        try {
            CharBuffer chars = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes));
            return chars.toString();
        } catch (CharacterCodingException e) {
            throw new IOException("file is not valid UTF-8", e);
        }
    }

    private static String required(Properties properties, String key) throws IOException {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IOException("manifest " + MANIFEST + " has no '" + key + "'");
        }
        return value.strip();
    }

    static List<String> policyNames(String listing) throws IOException {
        if (listing == null) {
            throw new IOException("manifest " + MANIFEST + " has no 'policies' key");
        }
        List<String> names = new ArrayList<>();
        if (listing.isBlank()) {
            return names;
        }
        for (String raw : listing.split(",", -1)) {
            String name = raw.strip();
            if (!POLICY_FILE.matcher(name).matches()) {
                throw new IOException("policy file name is not allowed: " + name.replaceAll("[^A-Za-z0-9._-]", "?"));
            }
            names.add(name);
        }
        if (names.size() > PolicySnapshot.MAX_POLICIES) {
            throw new IOException("manifest lists more than " + PolicySnapshot.MAX_POLICIES + " policy files");
        }
        return names;
    }
}
