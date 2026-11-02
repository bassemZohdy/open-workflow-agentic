package io.github.bassemzohdy.openworkflow.agentic.event;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bassemzohdy.openworkflow.agentic.contracts.common.CanonicalEncoder;
import io.github.bassemzohdy.openworkflow.agentic.contracts.event.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.UlidGenerator;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.*;
import io.github.bassemzohdy.openworkflow.agentic.runtime.event.*;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.*;
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
            Flyway.configure().dataSource(source).locations("classpath:db/migration","classpath:db/proposed-listen").load().migrate();
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
                root.resolve("apps/app-runtime/src/test/resources/db/proposed-listen/V81__listen_complete_match_identity.sql")));
    }

    @Test void realPreUpgradeSelectionRemainsReadableButCannotBeReplayed() throws Exception {
        String name="oa_match_upgrade_"+UUID.randomUUID().toString().replace("-","");
        try(var c=db("postgres").getConnection();var s=c.createStatement()) {s.execute("create database "+name);}
        try {
            var source=db(name);
            Flyway.configure().dataSource(source).locations("classpath:db/migration").target("77").load().migrate();
            var clock=Clock.systemUTC();var now=Instant.now().truncatedTo(ChronoUnit.MICROS);
            var owner=new Subject(SubjectKind.USER,"alice");
            var request=new StartRequest("upgrade-"+UUID.randomUUID(),owner,new DefinitionRef("agentic/upgrade","1"),Map.of(),"start");
            var registry=new JdbcInstanceStore(source);
            var row=registry.insertIfAbsent(InstanceRecord.starting(UlidGenerator.shared().next(),request,now),"test.start").record();
            row=registry.replace(row.successor(now,InstanceStatus.RUNNING,"engine-"+row.id(),1,null,null),row.version(),"test.running",owner);
            row=registry.replace(row.successor(now,InstanceStatus.WAITING,row.engineInstanceId(),1,null,null),row.version(),"test.waiting",owner);
            var task=new InboxTask(row.tenant(),row.id(),"app",row.engineInstanceId(),"agentic","upgrade","1","do/0/listen",1);
            String filter="{\"with\":{\"type\":\"order.ready\"}}";
            String snapshot="{\"format\":\"oa.inbox.listen-snapshot.v1\",\"task\":{\"listen\":{\"to\":{\"one\":"+filter+"}}}}";
            String filterHash=CanonicalEncoder.sha256Hex(new CanonicalEncoder("oa.inbox.event-filter.v1").text(filter).toBytes());
            var group=new InboxListenGroup(task,InboxListenGroup.Strategy.ONE,List.of(filterHash),InboxListenGroup.Stop.NONE,List.of(),null,InboxListenGroup.snapshotFingerprint(snapshot));
            var leases=new JdbcInboxTaskLease(source,event -> {},clock);
            var claim=leases.acquire(task,"upgrade-owner",Duration.ofSeconds(30)).orElseThrow().claim();
            var groups=new JdbcListenGroups(source,leases,event -> {},clock);
            groups.register(claim,group,snapshot,Duration.ofMinutes(5));
            String id="event-"+UUID.randomUUID();String event="{\"specversion\":\"1.0\",\"source\":\"urn:test:upgrade\",\"id\":\""+id+"\",\"type\":\"order.ready\"}";
            var receipt=new InboxMessage(row.tenant(),"urn:test:upgrade",id,"order.ready",row.id(),"route",event);
            new JdbcEventInbox(source,clock).receive(receipt,now.plusSeconds(600));
            var slot=group.slots(InboxListenGroup.Role.DATA).getFirst();
            // Reproduce the pre-V81 selection transaction with all owner, pinning, membership and FK triggers enabled.
            try(var c=source.getConnection()) {
                c.setAutoCommit(false);
                try {
                    try(var s=c.prepareStatement("insert into inbox.listen_item(tenant,instance_id,task_digest,sequence,role,source,event_id,receipt_fingerprint,matched_slots,selecting_owner,selecting_epoch) values(?,?,?,1,'DATA',?,?,?,?::jsonb,?,?)")) {
                        s.setString(1,row.tenant());s.setString(2,row.id().value());s.setString(3,task.digest());s.setString(4,receipt.source());s.setString(5,id);s.setString(6,receipt.fingerprint());s.setString(7,new ObjectMapper().writeValueAsString(List.of(slot)));s.setString(8,claim.owner());s.setLong(9,claim.epoch());assertEquals(1,s.executeUpdate());
                    }
                    try(var s=c.prepareStatement("insert into inbox.listen_member(tenant,instance_id,task_digest,sequence,role,ordinal,filter_fingerprint,exclusive_slot) values(?,?,?,1,'DATA',?,?,true)")) {
                        s.setString(1,row.tenant());s.setString(2,row.id().value());s.setString(3,task.digest());s.setInt(4,slot.ordinal());s.setString(5,slot.filterFingerprint());assertEquals(1,s.executeUpdate());
                    }
                    try(var s=c.prepareStatement("update inbox.listen_group set last_sequence=1,capture_complete=true,write_owner=?,write_epoch=? where tenant=? and instance_id=? and task_digest=?")) {
                        s.setString(1,claim.owner());s.setLong(2,claim.epoch());s.setString(3,row.tenant());s.setString(4,row.id().value());s.setString(5,task.digest());assertEquals(1,s.executeUpdate());
                    }
                    c.commit();
                } catch(Exception failure) {c.rollback();throw failure;}
            }
            Flyway.configure().dataSource(source).locations("classpath:db/migration","classpath:db/proposed-listen").load().migrate();
            var retained=groups.selected(claim,group,0,128);
            assertEquals(1,retained.size());assertEquals(receipt,retained.getFirst().receipt());
            assertEquals(List.of(slot),retained.getFirst().memberships());
            var selection=new SdkListenMatchSet(group,snapshot).matches(receipt);
            assertThrows(IllegalStateException.class,()->groups.offer(claim,group,receipt,selection));
            assertEquals(retained,groups.selected(claim,group,0,128));
            assertTrue(leases.release(claim));
            var successor=leases.acquire(task,"successor-owner",Duration.ofSeconds(30)).orElseThrow().claim();
            assertTrue(successor.epoch()>claim.epoch());
            assertThrows(IllegalStateException.class,()->groups.selected(claim,group,0,128));
            assertEquals(retained,groups.selected(successor,group,0,128));
            assertThrows(IllegalStateException.class,()->groups.offer(successor,group,receipt,selection));
            assertEquals(retained,groups.selected(successor,group,0,128));
        } finally {
            assertTrue(name.matches("oa_match_upgrade_[0-9a-f]{32}"));
            try(var c=db("postgres").getConnection();var s=c.createStatement()) {s.execute("drop database "+name);}
        }
    }
}
