package io.github.bassemzohdy.openworkflow.agentic.runtime.audit;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HexFormat;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.postgresql.ds.PGSimpleDataSource;

/**
 * A private, throwaway PostgreSQL database for the central append-once tests. It is migrated with the module's real
 * Flyway scripts up to the explicit pre-V75 foundation (target {@value #FOUNDATION_TARGET}) and then, when asked, with
 * GPT's PROPOSED {@code V75__audit_source_binding.sql} (an exact copy of the file at commit
 * 4a45515d7910e77f604fec1b151631531f13ec7c, pinned by SHA-256 below). Pinning the target keeps the proposal applied
 * exactly once and keeps the missing-schema database genuinely without {@code audit.source_binding} even after a
 * production V75 exists in {@code db/migration}. No migration number is allocated here.
 * The database is dropped when the JVM exits. Needs a role that may create databases (the CI and local {@code postgres}).
 */
final class AuditSourceTestDatabase implements AutoCloseable {

    static final String PROPOSAL_RESOURCE = "/audit/proposed/V75__audit_source_binding.sql";
    static final String PROPOSAL_SHA256 = "e9fc5598d64a0496d23001c6c4b381fd1f483d420839e6344efad9743f53d471";

    /** The last migration before the audit source binding; later production migrations are not applied here. */
    static final String FOUNDATION_TARGET = "74";

    private final String name = "oa_audit_src_" + UUID.randomUUID().toString().replace("-", "");
    private final DataSource data;

    private AuditSourceTestDatabase(boolean withProposal) {
        try (Connection admin = admin().getConnection(); Statement s = admin.createStatement()) {
            s.execute("create database " + name);
        } catch (SQLException e) {
            throw new IllegalStateException("cannot create the private audit test database", e);
        }
        Runtime.getRuntime().addShutdownHook(new Thread(this::dropQuietly));
        PGSimpleDataSource ds = plain(name);
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").target(FOUNDATION_TARGET).load().migrate();
        if (withProposal) {
            try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
                s.execute(proposal());
            } catch (SQLException e) {
                throw new IllegalStateException("the proposed V75 could not be applied", e);
            }
        }
        this.data = ds;
    }

    static AuditSourceTestDatabase withProposedV75() {
        return new AuditSourceTestDatabase(true);
    }

    /** The pre-V75 foundation only (target 74): no {@code audit.source_binding}, even once production V75 exists. */
    static AuditSourceTestDatabase withoutProposedV75() {
        return new AuditSourceTestDatabase(false);
    }

    DataSource dataSource() {
        return data;
    }

    String name() {
        return name;
    }

    /** A login role with exactly the given grants, connected to this database. */
    DataSource roleDataSource(String role, String password) {
        PGSimpleDataSource ds = plain(name);
        ds.setUser(role);
        ds.setPassword(password);
        return ds;
    }

    void execute(String... statements) throws SQLException {
        try (Connection c = data.getConnection(); Statement s = c.createStatement()) {
            for (String sql : statements) {
                s.execute(sql);
            }
        }
    }

    static String proposal() {
        try (InputStream in = AuditSourceTestDatabase.class.getResourceAsStream(PROPOSAL_RESOURCE)) {
            byte[] bytes = in.readAllBytes();
            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            if (!PROPOSAL_SHA256.equals(digest)) {
                throw new IllegalStateException("the proposed V75 copy is not the pinned file: " + digest);
            }
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException | java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void close() {
        dropQuietly();
    }

    private void dropQuietly() {
        try (Connection admin = admin().getConnection(); Statement s = admin.createStatement()) {
            s.execute("drop database if exists " + name + " with (force)");
        } catch (SQLException ignored) {
            // a leftover scratch database is harmless; it has a random name
        }
    }

    private static PGSimpleDataSource admin() {
        return plain("postgres");
    }

    private static PGSimpleDataSource plain(String database) {
        String base = AuditTestDatabase.URL;
        String withoutDb = base.substring(0, base.lastIndexOf('/') + 1);
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(withoutDb + database);
        ds.setUser(AuditTestDatabase.USER);
        ds.setPassword(AuditTestDatabase.PASSWORD);
        return ds;
    }
}
