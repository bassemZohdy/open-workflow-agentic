package io.github.bassemzohdy.openworkflow.agentic.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.UlidGenerator;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.Subject;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.SubjectKind;
import io.github.bassemzohdy.openworkflow.agentic.runtime.audit.AuditTrail;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.DelegationGrant;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.DelegationGrantStore;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.DelegationGrants;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.IdentityAudit;
import io.github.bassemzohdy.openworkflow.agentic.testkit.idp.FakeIdentityProvider;
import io.github.bassemzohdy.openworkflow.agentic.testkit.process.ManagedProcess;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

/**
 * Identity across a kill and a restart (WP-102 slice f, M11). The application runs as child JVMs on a fresh database,
 * with a verified caller (tokens of the testkit's fake issuer on port 18496), and is killed with SIGKILL.
 *
 * <p>Two instances run under two grants of the same user. While the application is down, one grant is revoked. After the
 * restart:
 * <ul>
 *   <li>the instance with the valid grant resumes with the same subject, grant id and correlation id, restored from the
 *       registry (nothing about them travelled in workflow state);</li>
 *   <li>the instance with the revoked grant does not run (no effect call), waits with the reason
 *       {@code reauthorization.required}, and the audit event and the inspect field say so;</li>
 *   <li>after the user renews it with a new grant, it resumes under the new grant with the original subject and
 *       correlation id.</li>
 * </ul>
 * Finally every table of the database and the process logs are scanned for the tokens that were used, the secrets that
 * were read and rotated, and their hex and base64 forms. The scanner is first shown to find a planted token.
 *
 * <p>Verified against the fake issuer only. Run by failsafe after {@code package}.
 */
class IdentityRecoveryIT {

    private static final Path JAR = Path.of("target/quarkus-app/quarkus-run.jar");
    private static final Path LOGS = Path.of("target/process-logs");
    private static final String SERVER_URL = System.getenv().getOrDefault("DB_URL",
            "jdbc:postgresql://localhost:5432/agentic");
    private static final String DB_USER = System.getenv().getOrDefault("DB_USER", "postgres");
    private static final String DB_PASSWORD = System.getenv().getOrDefault("DB_PASSWORD", "postgres");
    private static final int IDP_PORT = 18496;
    private static final String AUDIENCE = "oa-api";
    private static final String DEFINITION = "agentic/lifecycle-probe";
    private static final String EVENT = "org.openworkflow.agentic.lifecycle.probe";
    private static final String SECRET_NAME = "exchange-client-secret";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    private static String databaseName;
    private static String dbUrl;
    private static FakeIdentityProvider idp;
    private static Path secretsDir;

    private final List<ManagedProcess> started = new ArrayList<>();
    private final Set<String> tokens = new LinkedHashSet<>();

    @BeforeAll
    static void setUp() throws Exception {
        databaseName = "identity_it_" + UUID.randomUUID().toString().replace("-", "");
        String prefix = SERVER_URL.substring(0, SERVER_URL.lastIndexOf('/') + 1);
        try (Connection admin = DriverManager.getConnection(SERVER_URL, DB_USER, DB_PASSWORD);
             Statement statement = admin.createStatement()) {
            statement.execute("create database " + databaseName);
        }
        dbUrl = prefix + databaseName;
        idp = new FakeIdentityProvider(Clock.systemUTC()).start(IDP_PORT);
        secretsDir = Files.createTempDirectory("identity-it-secrets");
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (idp != null) {
            idp.close();
        }
        if (databaseName != null) {
            try (Connection admin = DriverManager.getConnection(SERVER_URL, DB_USER, DB_PASSWORD);
                 Statement statement = admin.createStatement()) {
                statement.execute("drop database if exists " + databaseName + " with (force)");
            }
        }
    }

    @AfterEach
    void stopEverything() throws InterruptedException {
        for (ManagedProcess process : started) {
            process.kill();
        }
        started.clear();
    }

    // ---- the test ------------------------------------------------------------------------------------------------------

    @Test
    void identitySurvivesAKillARevokedGrantHoldsTheInstanceAndNoCredentialIsPersisted() throws Exception {
        String tenant = "it-" + UUID.randomUUID();
        String canary1 = "CANARY-SECRET-" + UUID.randomUUID();
        Path secretFile = secretsDir.resolve(SECRET_NAME);
        Files.writeString(secretFile, canary1 + "\n");
        int port = ManagedProcess.freePort();

        // ---- run 1: two instances under two grants, killed while their effect calls are in flight -----------------------
        ManagedProcess first = app("identity-run1", port);
        DelegationGrants grants = grants();
        Subject alice = new Subject(SubjectKind.USER, "alice");
        Subject planner = new Subject(SubjectKind.AGENT, "planner");
        DelegationGrant keep = grants.grant(tenant, alice, alice, planner, Set.of("cases:read"),
                Instant.now().plus(Duration.ofHours(1)), null);
        DelegationGrant doomed = grants.grant(tenant, alice, alice, planner, Set.of("cases:read"),
                Instant.now().plus(Duration.ofHours(1)), null);

        String corrX = "corr-x-" + UUID.randomUUID().toString().substring(0, 8);
        String corrY = "corr-y-" + UUID.randomUUID().toString().substring(0, 8);
        String startToken = token("alice", tenant, "profile");
        String x = startInstance(first, startToken, corrX, keep.id().value());
        String y = startInstance(first, startToken, corrY, doomed.id().value());
        String engineX = engineId(first, x, startToken);
        String engineY = engineId(first, y, startToken);
        for (String[] each : new String[][] {{engineX, keep.id().value(), corrX}, {engineY, doomed.id().value(), corrY}}) {
            await("effect call and restored identity of " + each[0], Duration.ofSeconds(60), () -> read(first.logFile())
                    .contains("probe-effect authority instance=" + each[0] + " subject=alice grant=" + each[1]
                            + " correlation=" + each[2] + " state=AUTHORIZED epoch=0"));
        }
        assertEquals("alice", scalar("select subject_id from identity.instance_authority where instance_id = ?", x));

        // A secret is read and rotated in the running process (section 6): only its length is ever reported.
        assertEquals(canary1.length(), secretLength(first));
        String canary2 = "CANARY-ROTATED-SECRET-" + UUID.randomUUID();
        Files.writeString(secretFile, canary2 + "\n");
        Files.setLastModifiedTime(secretFile, FileTime.fromMillis(System.currentTimeMillis() + 5_000));
        await("the rotated secret is read", Duration.ofSeconds(20), () -> secretLength(first) == canary2.length());
        first.kill();

        // ---- while the application is down, the user withdraws one grant -----------------------------------------------
        grants.revoke(doomed.id(), alice, "user withdrew it while the system was down", null);

        // ---- run 2: the valid instance resumes with its identity, the other is held -------------------------------------
        ManagedProcess second = app("identity-run2", port);
        String bearer = token("alice", tenant, "profile");
        await("x resumes with epoch 1 and the same identity", Duration.ofSeconds(90), () -> read(second.logFile())
                .contains("probe-effect authority instance=" + engineX + " subject=alice grant=" + keep.id().value()
                        + " correlation=" + corrX + " state=AUTHORIZED epoch=1"));
        await("y is held", Duration.ofSeconds(60), () -> count("select count(*) from identity.instance_authority "
                + "where instance_id = ? and state = 'REAUTHORIZATION_REQUIRED'", y) == 1);
        await("y is WAITING", Duration.ofSeconds(30), () -> {
            try {
                return "WAITING".equals(status(second, y, bearer));
            } catch (Exception e) {
                return false;
            }
        });
        Thread.sleep(7_000);   // several reconciler passes (PT2S): a held instance must stay held
        assertFalse(read(second.logFile()).contains("probe-effect received instance=" + engineY + " epoch=1"),
                "the step of the instance with the revoked grant must not run");
        assertEquals("grant.revoked", scalar("select state_reason from identity.instance_authority where instance_id = ?", y));
        assertEquals(1, count("select count(*) from task.instance_event where instance_id = ? "
                + "and reason = 'reauthorization.required'", y), "one history row, not one per pass");
        assertEquals(1, count("select count(*) from audit.event where event_type = 'identity.reauthorization.required' "
                + "and resource_id = ?", y), "one audit event");
        assertEquals(0, count("select count(*) from audit.event where event_type = 'identity.reauthorization.required' "
                + "and resource_id = ?", x));
        String ops = token("ops", tenant, "oa.inspect");
        await("the task view carries the reason", Duration.ofSeconds(30), () -> {
            try {
                return "reauthorization.required".equals(
                        get(second, "/operations/instances/" + y, ops).path("taskView").path("lastReason").asText());
            } catch (Throwable e) {
                return false;
            }
        });
        JsonNode inspect = get(second, "/operations/instances/" + y, ops);
        assertEquals("REAUTHORIZATION_REQUIRED", inspect.path("authority").path("state").asText());
        assertEquals("grant.revoked", inspect.path("authority").path("reason").asText());
        assertEquals("alice", inspect.path("authority").path("subject").asText());
        assertEquals(corrY, inspect.path("authority").path("correlationId").asText());

        // ---- the user renews: the held instance resumes under the new grant, with its original identity ----------------
        DelegationGrant renewed = grants.grant(tenant, alice, alice, planner, Set.of("cases:read"),
                Instant.now().plus(Duration.ofHours(1)), null);
        post(second, "/instances/" + y + "/reauthorize", bearer, null,
                "{\"delegationGrantId\":\"" + renewed.id().value() + "\"}");
        await("y resumes under the new grant with its original subject and correlation", Duration.ofSeconds(90),
                () -> read(second.logFile()).contains("probe-effect authority instance=" + engineY
                        + " subject=alice grant=" + renewed.id().value() + " correlation=" + corrY
                        + " state=AUTHORIZED epoch=1"));
        decideUntilCompleted(second, x, engineX, bearer);
        decideUntilCompleted(second, y, engineY, bearer);

        // ---- no credential in any stored state ---------------------------------------------------------------------------
        List<String> needles = needles(canary1, canary2);
        assertScannerFindsAPlantedToken(startToken);
        List<String> hits = scanDatabase(needles);
        assertTrue(hits.isEmpty(), "credentials found in stored state: " + hits);
        for (Path log : List.of(Path.of("target/process-logs/identity-run1.log"),
                Path.of("target/process-logs/identity-run2.log"))) {
            String text = read(log);
            for (String needle : needles) {
                assertFalse(text.contains(needle), "a credential appears in " + log.getFileName());
            }
        }
        assertTrue(count("select count(*) from audit.event where event_type = 'identity.secret.rotated' "
                + "and resource_id = ?", SECRET_NAME) >= 1, "the rotation is audited by name and generation");
        assertEquals(0, count("select count(*) from audit.event where attributes::text like ? or attributes::text like ?",
                "%" + canary1 + "%", "%" + canary2 + "%"));
    }

    // ---- process and HTTP helpers ------------------------------------------------------------------------------------

    private ManagedProcess app(String label, int port) throws IOException, InterruptedException {
        Files.createDirectories(LOGS);
        ManagedProcess process = ManagedProcess.jar(JAR)
                .httpPort(port)
                .logFile(LOGS.resolve(label + ".log"))
                .property("quarkus.datasource.jdbc.url", dbUrl)
                // The packaged jar runs in the production profile; a verified caller needs no development seam.
                .property("quarkus.profile", "itest")
                .property("agentic.dev.lifecycle-probes", "true")
                .property("agentic.identity.issuer", idp.issuer())
                .property("agentic.identity.jwks-uri", idp.jwksUri())
                .property("agentic.identity.audience", AUDIENCE)
                .property("agentic.secrets.dir", secretsDir.toString())
                .property("agentic.secrets.ttl", "PT1S")
                .property("agentic.reconciler.enabled", "true")
                .property("agentic.reconciler.interval", "PT2S")
                .property("agentic.reconciler.start-stale-after", "PT2S")
                .property("agentic.reconciler.start-backoff", "PT1S")
                .property("agentic.reconciler.cancel-reissue-after", "PT2S")
                .build();
        process.start();
        started.add(process);
        Path log = process.logFile();
        await(label + " ready", Duration.ofSeconds(150), () -> process.isAlive()
                && read(log).contains("Workflow definitions warmup complete")
                && read(log).contains("lifecycle reconciler: startup pass complete")
                && process.httpStatus("/q/health/ready") == 200);
        return process;
    }

    private String token(String subject, String tenant, String scope) throws Exception {
        Map<String, Object> claims = idp.baseClaims(subject, AUDIENCE, Duration.ofMinutes(10));
        claims.put("scope", scope);
        claims.put("tenant", tenant);
        String token = idp.signClaims(claims);
        tokens.add(token);
        return token;
    }

    private static HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        return HTTP.send(request.timeout(Duration.ofSeconds(60)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static JsonNode post(ManagedProcess process, String path, String bearer, String correlation, String body)
            throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(process.baseUrl() + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        if (correlation != null) {
            request.header("X-Correlation-Id", correlation);
        }
        HttpResponse<String> response = send(request);
        assertTrue(response.statusCode() == 200 || response.statusCode() == 202,
                "POST " + path + " answered " + response.statusCode() + ": " + response.body());
        return JSON.readTree(response.body());
    }

    private static JsonNode get(ManagedProcess process, String path, String bearer) throws Exception {
        HttpResponse<String> response = send(HttpRequest.newBuilder(URI.create(process.baseUrl() + path))
                .header("Authorization", "Bearer " + bearer).GET());
        assertEquals(200, response.statusCode(), response.body());
        return JSON.readTree(response.body());
    }

    private static String status(ManagedProcess process, String id, String bearer) throws Exception {
        return get(process, "/instances/" + id, bearer).get("status").asText();
    }

    private static String engineId(ManagedProcess process, String id, String bearer) throws Exception {
        return get(process, "/instances/" + id, bearer).path("engineInstanceId").asText();
    }

    private static String startInstance(ManagedProcess process, String bearer, String correlation, String grantId)
            throws Exception {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("effectUrl", process.baseUrl() + "/dev/lifecycle/effects");
        input.put("mode", "hold");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("definitionId", DEFINITION);
        body.put("definitionVersion", "0.1.0");
        body.put("input", input);
        body.put("idempotencyKey", "k-" + UUID.randomUUID());
        body.put("delegationGrantId", grantId);
        return post(process, "/instances", bearer, correlation, JSON.writeValueAsString(body)).get("id").asText();
    }

    private static long secretLength(ManagedProcess process) {
        try {
            HttpResponse<String> response = send(HttpRequest.newBuilder(
                    URI.create(process.baseUrl() + "/dev/lifecycle/secret-probe?name=" + SECRET_NAME)).GET());
            return response.statusCode() == 200 ? JSON.readTree(response.body()).path("length").asLong() : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    private static void decideUntilCompleted(ManagedProcess process, String id, String engineId, String bearer)
            throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
        while (System.nanoTime() < deadline) {
            if ("COMPLETED".equals(status(process, id, bearer))) {
                return;
            }
            post(process, "/dev/lifecycle/events", null, null, "{\"type\":\"" + EVENT + "\"}");
            Thread.sleep(500);
        }
        // CI keeps the failsafe XML but not target/process-logs, so the evidence has to travel in the message.
        // The snapshot is evidence only: a diagnostic failure must never replace this failure (no claim about the cause).
        String registryStatus;
        try {
            registryStatus = status(process, id, bearer);
        } catch (Throwable unavailable) {
            registryStatus = "unavailable";
        }
        fail("instance " + id + " did not complete; status " + registryStatus
                + "; engine instance " + engineId + "; "
                + RecoveryEngineSnapshot.describe(process.baseUrl(), DEFINITION, "0.1.0", engineId)
                + "\n" + logTail(process.logFile(), 60));
    }

    private static String logTail(Path log, int lines) {
        String[] all = read(log).split("\n");
        return String.join("\n", java.util.Arrays.copyOfRange(all, Math.max(0, all.length - lines), all.length));
    }

    private static void await(String what, Duration timeout, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(200);
        }
        fail("timed out waiting for " + what);
    }

    private static String read(Path log) {
        try {
            return Files.exists(log) ? Files.readString(log) : "";
        } catch (IOException e) {
            return "";
        }
    }

    // ---- database helpers ----------------------------------------------------------------------------------------------

    private static DelegationGrants grants() {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(dbUrl);
        ds.setUser(DB_USER);
        ds.setPassword(DB_PASSWORD);
        return new DelegationGrants(new DelegationGrantStore(ds),
                new IdentityAudit(new AuditTrail(ds), Clock.systemUTC(), UlidGenerator.shared()), Clock.systemUTC(),
                UlidGenerator.shared(), Duration.ofDays(30));
    }

    private static long count(String sql, Object... params) {
        try (Connection connection = DriverManager.getConnection(dbUrl, DB_USER, DB_PASSWORD);
             PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                statement.setObject(i + 1, params[i]);
            }
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String scalar(String sql, String param) throws Exception {
        try (Connection connection = DriverManager.getConnection(dbUrl, DB_USER, DB_PASSWORD);
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, param);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    // ---- the canary scan -----------------------------------------------------------------------------------------------

    /** Every form in which a credential could have been stored: as is, its signature or payload part, hex, base64. */
    private List<String> needles(String... secrets) {
        Set<String> out = new LinkedHashSet<>();
        for (String token : tokens) {
            out.add(token);
            String[] parts = token.split("\\.");
            out.add(parts[1]);
            out.add(parts[2]);
            addEncodings(out, token);
        }
        for (String secret : secrets) {
            out.add(secret);
            addEncodings(out, secret);
        }
        return new ArrayList<>(out);
    }

    private static void addEncodings(Set<String> out, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.add(HexFormat.of().formatHex(bytes));
        out.add(Base64.getEncoder().encodeToString(bytes));
        out.add(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes));
    }

    /** Looks at the text form of every row of every table outside the system schemas. */
    private static List<String> scanDatabase(List<String> needles) throws Exception {
        List<String> hits = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection(dbUrl, DB_USER, DB_PASSWORD)) {
            List<String> tables = new ArrayList<>();
            try (Statement statement = connection.createStatement();
                 ResultSet rs = statement.executeQuery("select quote_ident(table_schema) || '.' || quote_ident(table_name) "
                         + "from information_schema.tables where table_type = 'BASE TABLE' "
                         + "and table_schema not in ('pg_catalog', 'information_schema')")) {
                while (rs.next()) {
                    tables.add(rs.getString(1));
                }
            }
            assertTrue(tables.size() > 10, "the scan must cover the application's tables, found " + tables.size());
            for (String table : tables) {
                for (String needle : needles) {
                    try (PreparedStatement statement = connection.prepareStatement(
                            "select count(*) from " + table + " t where position(? in t::text) > 0")) {
                        statement.setString(1, needle);
                        try (ResultSet rs = statement.executeQuery()) {
                            rs.next();
                            if (rs.getLong(1) > 0) {
                                hits.add(table + " contains a credential of " + needle.length() + " characters");
                            }
                        }
                    }
                }
            }
        }
        return hits;
    }

    /** The scanner must be able to fail: plant a token in a table and see it found. */
    private void assertScannerFindsAPlantedToken(String token) throws Exception {
        try (Connection connection = DriverManager.getConnection(dbUrl, DB_USER, DB_PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.execute("create table public.canary_control (v text)");
            try (PreparedStatement insert = connection.prepareStatement("insert into public.canary_control values (?)")) {
                insert.setString(1, "prefix " + token + " suffix");
                insert.executeUpdate();
            }
        }
        try {
            List<String> planted = scanDatabase(needles("unused-secret-value"));
            assertTrue(planted.stream().anyMatch(h -> h.contains("canary_control")),
                    "the scanner must find a planted token, found: " + planted);
        } finally {
            try (Connection connection = DriverManager.getConnection(dbUrl, DB_USER, DB_PASSWORD);
                 Statement statement = connection.createStatement()) {
                statement.execute("drop table public.canary_control");
            }
        }
    }
}
