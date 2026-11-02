package io.github.bassemzohdy.openworkflow.agentic.runtime.human;

import static org.junit.jupiter.api.Assertions.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.event.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.HumanDecision;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import java.nio.file.*;
import java.time.Duration;
import java.util.Properties;
import org.junit.jupiter.api.Test;

/** Explicitly launched phases; the producer halts and a fresh JVM must reconstruct the workflow. */
@QuarkusTest
@QuarkusTestResource(value=HumanSchemaResource.class,restrictToAnnotatedClass=true)
@jakarta.enterprise.inject.Typed(HumanWakeProcessPhase.class)
class HumanWakeProcessPhase extends HumanWakeCheckpointTest {
    private Path manifest() {return Path.of(System.getenv("HUMAN_CHECKPOINT_MANIFEST"));}
    @Test void checkpointAndCrash() throws Exception {execute(false,false,false,manifest());fail("writer must halt");}
    @Test void reserveAndCrash() throws Exception {crashAfterReservation(manifest());fail("reservation owner must halt");}
    @Test void restoreAfterReservationCrash() throws Exception {
        var p=new Properties();try(var in=Files.newInputStream(manifest())){p.load(in);}
        var row=registry.find("tenant",Ulid.parse(p.getProperty("instance"))).orElseThrow();
        var task=Ulid.parse(p.getProperty("task"));
        var wait=retainedWait(row,p.getProperty("decision"),p.getProperty("application"));
        var decision=new HumanDecision(Ulid.parse(p.getProperty("decision")),row.tenant(),task,0,ALICE,HumanDecision.Outcome.APPROVE,"private");
        restoreReservedWake(row,p.getProperty("application"),wait,decision);
    }
    @Test void restoreAfterCrash() throws Exception {
        var p=new Properties();try(var in=Files.newInputStream(manifest())){p.load(in);}
        var row=registry.find("tenant",Ulid.parse(p.getProperty("instance"))).orElseThrow();
        var task=Ulid.parse(p.getProperty("task"));
        var wait=retainedWait(row,p.getProperty("decision"),p.getProperty("application"));
        var decision=new HumanDecision(Ulid.parse(p.getProperty("decision")),row.tenant(),task,0,ALICE,HumanDecision.Outcome.APPROVE,"private");
        var message=HumanDecisionWake.message(decision,wait);
        assertTrue(proof.consumed(wait,message));assertEquals("DISPATCHED",deliveryState(wait));
        assertEquals(0,acknowledgementAudits(row));
        restoreCheckpoint(row,p.getProperty("application"),wait,message);
        assertEquals("ACKNOWLEDGED",deliveryState(wait));assertEquals(1,acknowledgementAudits(row));
    }
    private InboxWait retainedWait(io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.InstanceRecord row,
                                   String decisionId,String application) throws Exception {
        String key;
        try(var c=dataSource.getConnection();var q=c.prepareStatement("select wait_key from human_proposal.decision where tenant=? and instance_id=? and decision_id=?")) {
            q.setString(1,row.tenant());q.setString(2,row.id().value());q.setString(3,decisionId);
            try(var r=q.executeQuery()){assertTrue(r.next());key=r.getString(1);}
        }
        var wait=dispatch.findProfile(row.tenant(),row.id(),key).orElseThrow();
        assertEquals(application,wait.applicationId());assertEquals(row.engineInstanceId(),wait.engineInstanceId());return wait;
    }
}
