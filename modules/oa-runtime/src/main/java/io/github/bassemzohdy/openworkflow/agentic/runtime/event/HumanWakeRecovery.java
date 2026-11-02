package io.github.bassemzohdy.openworkflow.agentic.runtime.event;

import io.github.bassemzohdy.openworkflow.agentic.contracts.common.Checks;
import io.github.bassemzohdy.openworkflow.agentic.contracts.event.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.HumanDecision;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.Subject;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.SubjectKind;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.Transactions;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;

/** Explicit isolated-schema reader for retained recovery data; it is not an authorization or a dispatch service. */
public final class HumanWakeRecovery {
    public enum State { BLOCKED, STAGED, CHECKPOINTED, ACKNOWLEDGED, TERMINAL }
    public static final class Retained {
        private final HumanDecision decision;private final InboxWait wait;private final State state;
        private Retained(HumanDecision decision,InboxWait wait,State state){this.decision=decision;this.wait=wait;this.state=state;}
        public HumanDecision decision(){return decision;} public InboxWait waitProfile(){return wait;} public State state(){return state;}
        @Override public String toString(){return "RetainedHumanWake[state="+state+", data=redacted]";}
    }
    private record DecisionRow(HumanDecision decision,Ulid instance,String waitKey) { }
    private final DataSource dataSource;
    private final JdbcInboxCheckpointProof proof;
    public HumanWakeRecovery(DataSource dataSource) {
        this.dataSource=Objects.requireNonNull(dataSource);this.proof=new JdbcInboxCheckpointProof(dataSource);
    }
    /** Returns retained facts only. Caller must be a separately authorized internal recovery process. */
    public Optional<Retained> load(String tenant,Ulid decisionId) {
        Checks.requireText("tenant",tenant,256);Objects.requireNonNull(decisionId);
        return Transactions.inTransaction(dataSource,c->{
            var row=readDecision(c,tenant,decisionId);if(row.isEmpty())return Optional.empty();
            var data=row.get();
            var wait=WaitProfiles.load(c,tenant,data.instance(),data.waitKey())
                    .orElseThrow(()->new IllegalStateException("retained human wait profile is unavailable"));
            JdbcInboxDispatch.instance(c,wait); // Registry lock precedes task/pending and delivery locks.
            String stage=HumanWakeStage.requireDecision(c,data.decision(),wait);
            var expected=HumanDecisionWake.message(data.decision(),wait);
            var actual=JdbcInboxDispatch.signal(c,wait);
            if("BLOCKED".equals(stage)) {
                if(actual.isPresent()||stageExists(c,tenant,decisionId))throw new IllegalStateException("blocked human wake has a retained signal");
            } else if("STAGED".equals(stage)) {
                HumanWakeStage.requireExistingStage(c,data.decision(),wait,expected);
                if(actual.isEmpty()||!expected.equals(actual.get()))throw new IllegalStateException("retained human wake signal differs");
            } else throw new IllegalStateException("retained human wake state is invalid");
            String delivery=delivery(c,wait);boolean consumed=actual.isPresent()&&proof.consumed(c,wait,actual.get());
            State state=switch(delivery) {
                case "CANCELLED","EXPIRED" -> State.TERMINAL;
                case "ACKNOWLEDGED" -> {if(!consumed)throw new IllegalStateException("acknowledged human wake has no checkpoint");yield State.ACKNOWLEDGED;}
                case "DISPATCHED" -> {if(!"STAGED".equals(stage))throw new IllegalStateException("unstaged human wake was dispatched");yield consumed?State.CHECKPOINTED:State.STAGED;}
                case "REGISTERED" -> {if(consumed)throw new IllegalStateException("registered human wake has a checkpoint");yield "BLOCKED".equals(stage)?State.BLOCKED:State.STAGED;}
                default -> throw new IllegalStateException("human wake delivery state is invalid");
            };
            return Optional.of(new Retained(data.decision(),wait,state));
        });
    }
    private static Optional<DecisionRow> readDecision(Connection c,String tenant,Ulid id) throws SQLException {
        try(var q=c.prepareStatement("select task_id,expected_version,actor_id,outcome,content,fingerprint,instance_id,wait_key "
                +"from human_proposal.decision where tenant=? and decision_id=?")) {
            q.setString(1,tenant);q.setString(2,id.value());
            try(var r=q.executeQuery()) {
                if(!r.next())return Optional.empty();
                try {
                    var decision=new HumanDecision(id,tenant,Ulid.parse(r.getString(1)),r.getLong(2),
                            new Subject(SubjectKind.USER,r.getString(3)),HumanDecision.Outcome.valueOf(r.getString(4)),r.getString(5));
                    if(!decision.fingerprint().equals(r.getString(6)))throw new IllegalStateException();
                    return Optional.of(new DecisionRow(decision,Ulid.parse(r.getString(7)),r.getString(8)));
                } catch(RuntimeException invalid) {throw new IllegalStateException("retained human decision is invalid");}
            }
        }
    }
    private static boolean stageExists(Connection c,String tenant,Ulid decision) throws SQLException {
        try(var q=c.prepareStatement("select 1 from human_proposal.wake_stage where tenant=? and decision_id=?")) {
            q.setString(1,tenant);q.setString(2,decision.value());try(var r=q.executeQuery()){return r.next();}
        }
    }
    private static String delivery(Connection c,InboxWait wait) throws SQLException {
        try(var q=c.prepareStatement("select state,identity_digest from inbox.delivery where tenant=? and instance_id=? and wait_key=? for update")) {
            q.setString(1,wait.tenant());q.setString(2,wait.instanceId().value());q.setString(3,wait.key());
            try(var r=q.executeQuery()) {
                if(!r.next()||!wait.digest().equals(r.getString(2)))throw new IllegalStateException("human wake delivery identity differs");
                return r.getString(1);
            }
        }
    }
}
