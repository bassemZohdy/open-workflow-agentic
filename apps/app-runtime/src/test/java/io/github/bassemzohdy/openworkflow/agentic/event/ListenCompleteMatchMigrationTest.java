package io.github.bassemzohdy.openworkflow.agentic.event;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.sql.*;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

/** Upgrade rehearsal in an isolated database; no deployed role or callback claim. */
class ListenCompleteMatchMigrationTest {
    private static PGSimpleDataSource db(String name) {
        var ds=new PGSimpleDataSource();ds.setURL(System.getenv().getOrDefault("DB_URL","jdbc:postgresql://localhost:5432/agentic"));
        ds.setDatabaseName(name);ds.setUser(System.getenv().getOrDefault("DB_USER","postgres"));ds.setPassword(System.getenv().getOrDefault("DB_PASSWORD","postgres"));return ds;
    }
    @Test void legacyRowsSurviveAndNewRowsRequireFullMatchIdentity() throws Exception {
        String name="oa_match_upgrade_"+UUID.randomUUID().toString().replace("-","");
        try(var c=db("postgres").getConnection();var s=c.createStatement()) {s.execute("create database "+name);}
        try {
            var source=db(name);Flyway.configure().dataSource(source).locations("classpath:db/migration").target("77").load().migrate();
            try(var c=source.getConnection();var s=c.createStatement()) {
                // Synthetic legacy fixture deliberately bypasses FK/owner triggers; the migration must preserve it.
                s.execute("set session_replication_role=replica");
                s.execute("insert into inbox.listen_item(tenant,instance_id,task_digest,sequence,role,source,event_id,receipt_fingerprint,matched_slots,selecting_owner,selecting_epoch) values('legacy','i','t',1,'DATA','s','e','"+"a".repeat(64)+"','[{}]'::jsonb,'o',1)");
                s.execute("set session_replication_role=origin");
            }
            Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
            try(var c=source.getConnection();var s=c.createStatement()) {
                try(var rows=s.executeQuery("select full_match_fingerprint from inbox.listen_item where tenant='legacy'")) {assertTrue(rows.next());assertNull(rows.getString(1));}
                s.execute("set session_replication_role=replica");
                var failure=assertThrows(SQLException.class,()->s.execute("insert into inbox.listen_item(tenant,instance_id,task_digest,sequence,role,source,event_id,receipt_fingerprint,matched_slots,selecting_owner,selecting_epoch) values('new','i','t',1,'DATA','s','e','"+"a".repeat(64)+"','[{}]'::jsonb,'o',1)"));
                assertEquals("23514",failure.getSQLState());
            }
        } finally {
            assertTrue(name.matches("oa_match_upgrade_[0-9a-f]{32}"));
            try(var c=db("postgres").getConnection();var s=c.createStatement()) {s.execute("drop database "+name);}
        }
    }
    @Test void proposedSqlMatchesTestMigration() throws Exception {
        Path root=Path.of("").toAbsolutePath();
        while(root!=null && !Files.exists(root.resolve("deploy/sql/proposed-listen-complete-match.sql")))root=root.getParent();
        assertNotNull(root);
        assertEquals(-1,Files.mismatch(root.resolve("deploy/sql/proposed-listen-complete-match.sql"),
                root.resolve("apps/app-runtime/src/test/resources/db/migration/V81__listen_complete_match_identity.sql")));
    }
}
