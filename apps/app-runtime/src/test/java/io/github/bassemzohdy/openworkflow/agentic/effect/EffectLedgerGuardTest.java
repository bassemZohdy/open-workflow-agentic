package io.github.bassemzohdy.openworkflow.agentic.effect;

import static org.junit.jupiter.api.Assertions.*;

import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.*;
import io.github.bassemzohdy.openworkflow.agentic.runtime.effect.JdbcEffectLedger;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.sql.*;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Direct SQL must not bypass the durable identities and irreversible transitions used by T03. */
@QuarkusTest
class EffectLedgerGuardTest {
    @Inject JdbcEffectLedger ledger;
    @Inject javax.sql.DataSource dataSource;

    @Test
    void everyStateMoveMatchesTheExistingContractIncludingTerminalAndRepeatedStates() throws Exception {
        for(var from:EffectState.values()) for(var to:EffectState.values()) {
            var command=prepared("state-"+from+"-"+to);
            if(from!=EffectState.INTENDED) {
                ledger.recordOutcome(command.key(),from==EffectState.COMPENSATED?EffectState.CONFIRMED:from,"proof");
                if(from==EffectState.COMPENSATED) ledger.recordOutcome(command.key(),from,"undo");
            }
            String sql="update effect.ledger set state='"+to.name()+"'";
            if(from==to || from.canMoveTo(to)) assertEquals(1,update(command.key(),sql));
            else rejected(()->update(command.key(),sql));
            assertEquals(from==to || from.canMoveTo(to)?to:from,ledger.find(command.key()).orElseThrow().state());
        }
    }

    @Test
    void intentIdentityFingerprintPurposeAndReplayDeadlineCannotBeRebound() throws Exception {
        var command=prepared("identity");
        for(String assignment:new String[]{"thread_name='ws1-"+"a".repeat(64)+"'","node_id='other'",
                "idempotency_key='other'","target='test:other'","request_fingerprint='"+"a".repeat(64)+"'",
                "request_fingerprint=null","purpose='COMPENSATION'","created_at=created_at+interval '1 second'",
                "replay_until=replay_until+interval '1 second'","replay_until=null"})
            rejected(()->update(command.key(),"update effect.ledger set "+assignment));
        assertEquals(command.requestFingerprint(),scalar(command.key(),"request_fingerprint"));
        assertEquals(command.target(),ledger.find(command.key()).orElseThrow().target());
    }

    @Test
    void attemptedIsMonotoneAndResolvedEvidenceIsNotRewritten() throws Exception {
        var command=prepared("attempt");
        ledger.markAttempted(command.key());
        rejected(()->update(command.key(),"update effect.ledger set attempted=false"));
        var done=ledger.recordOutcome(command.key(),EffectState.CONFIRMED,"authoritative proof");
        for(String assignment:new String[]{"detail='replacement'","recorded_at=recorded_at+interval '1 second'"})
            rejected(()->update(command.key(),"update effect.ledger set "+assignment));
        assertEquals(done,ledger.recordOutcome(command.key(),EffectState.CONFIRMED,"duplicate caller"));
        var notAttempted=prepared("no-attempt");
        ledger.recordOutcome(notAttempted.key(),EffectState.FAILED,"authoritative refusal");
        rejected(()->update(notAttempted.key(),"update effect.ledger set attempted=true"));
    }

    @Test
    void newRowsCannotPretendToAlreadyHaveAnOutcomeOrDispatch() throws Exception {
        for(String suffix:new String[]{"'CONFIRMED',false","'FAILED',false","'COMPENSATED',false","'INTENDED',true"}) {
            var command=JdbcEffectLedgerTest.command("bad-insert");
            rejected(()->{
                try(var c=dataSource.getConnection();var s=c.prepareStatement("insert into effect.ledger "
                        +"(thread_name,node_id,idempotency_key,target,recorded_at,state,attempted) values (?,?,?,'test:target',clock_timestamp(),"+suffix+")")) {
                    bind(s,command.key());return s.executeUpdate();
                }
            });
            assertTrue(ledger.find(command.key()).isEmpty());
        }
    }

    @Test
    void journalLinksAndIntentsAreRetained() throws Exception {
        var original=prepared("charge");
        ledger.recordOutcome(original.key(),EffectState.CONFIRMED,"paid");
        var undo=EffectCommand.of(original.key().threadId(),"refund","1","test:payments","amount=10");
        ledger.prepareCompensation(original.key(),undo,Duration.ZERO,Instant.now());
        for(String sql:new String[]{"delete from effect.ledger","delete from effect.event","delete from effect.compensation",
                "update effect.event set reason='replacement'","update effect.compensation set compensation_node='replacement'"})
            rejected(()->update(original.key(),sql));
        assertEquals(EffectState.CONFIRMED,ledger.find(original.key()).orElseThrow().state());
        assertEquals(EffectState.INTENDED,ledger.find(undo.key()).orElseThrow().state());
    }

    private EffectCommand prepared(String node) {
        var command=JdbcEffectLedgerTest.command(node);
        ledger.prepare(command,Duration.ofMinutes(5),Instant.now());return command;
    }
    private int update(EffectKey key,String sql) throws SQLException {
        try(var c=dataSource.getConnection();var s=c.prepareStatement(sql+" where thread_name=? and node_id=? and idempotency_key=?")) {
            bind(s,key);return s.executeUpdate();
        }
    }
    private String scalar(EffectKey key,String column) throws SQLException {
        try(var c=dataSource.getConnection();var s=c.prepareStatement("select "+column+" from effect.ledger where thread_name=? and node_id=? and idempotency_key=?")) {
            bind(s,key);try(var r=s.executeQuery()) { assertTrue(r.next());return r.getString(1); }
        }
    }
    @FunctionalInterface private interface SqlWork { int run() throws SQLException; }
    private static void rejected(SqlWork work) { assertEquals("23514",assertThrows(SQLException.class,work::run).getSQLState()); }
    private static void bind(PreparedStatement s,EffectKey key) throws SQLException {
        s.setString(1,key.threadId().value());s.setString(2,key.nodeId());s.setString(3,key.idempotencyKey());
    }
}
