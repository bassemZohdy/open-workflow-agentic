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
    @Test void restoreAfterCrash() throws Exception {
        var p=new Properties();try(var in=Files.newInputStream(manifest())){p.load(in);}
        var row=registry.find("tenant",Ulid.parse(p.getProperty("instance"))).orElseThrow();
        var task=Ulid.parse(p.getProperty("task"));
        var wait=new InboxWait(row.tenant(),row.id(),p.getProperty("application"),row.engineInstanceId(),
                "agentic","human-checkpoint-test","1.0.0",p.getProperty("position"),Integer.parseInt(p.getProperty("iteration")),
                HumanDecisionWake.route(task),HumanDecisionWake.TYPE,HASH,Duration.ofMinutes(2));
        var decision=new HumanDecision(Ulid.parse(p.getProperty("decision")),row.tenant(),task,0,ALICE,HumanDecision.Outcome.APPROVE,"private");
        var message=HumanDecisionWake.message(decision,wait);
        assertTrue(proof.consumed(wait,message));assertEquals("DISPATCHED",deliveryState(wait));
        assertEquals(0,acknowledgementAudits(row));
        restoreCheckpoint(row,p.getProperty("application"),wait,message);
        assertEquals("ACKNOWLEDGED",deliveryState(wait));assertEquals(1,acknowledgementAudits(row));
    }
}
