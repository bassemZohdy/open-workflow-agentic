package io.github.bassemzohdy.openworkflow.agentic.runtime.human;

import static org.junit.jupiter.api.Assertions.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.event.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid;
import io.github.bassemzohdy.openworkflow.agentic.runtime.event.HumanWakeRecovery;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import java.nio.file.*;
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
        var retained=discover(row.tenant(),row.id());
        assertEquals(HumanWakeRecovery.State.STAGED,retained.state());var wait=retained.waitProfile();var decision=retained.decision();
        assertEquals(p.getProperty("application"),wait.applicationId());
        restoreReservedWake(row,p.getProperty("application"),wait,decision);
    }
    private HumanWakeRecovery.Retained discover(String tenant,Ulid instance) {
        var recovery=new HumanWakeRecovery(dataSource); Ulid cursor=null;
        while(true) {
            var page=recovery.scan(tenant,cursor,2);
            if(page.isEmpty())throw new IllegalStateException("retained human decision was not discovered");
            for(var id:page) {
                var retained=recovery.load(tenant,id).orElseThrow();
                if(retained.waitProfile().instanceId().equals(instance))return retained;
                cursor=id;
            }
        }
    }
    @Test void restoreAfterCrash() throws Exception {
        var p=new Properties();try(var in=Files.newInputStream(manifest())){p.load(in);}
        var row=registry.find("tenant",Ulid.parse(p.getProperty("instance"))).orElseThrow();
        var retained=discover(row.tenant(),row.id());
        assertEquals(HumanWakeRecovery.State.CHECKPOINTED,retained.state());var wait=retained.waitProfile();var decision=retained.decision();
        assertEquals(p.getProperty("application"),wait.applicationId());
        var message=HumanDecisionWake.message(decision,wait);
        assertTrue(proof.consumed(wait,message));assertEquals("DISPATCHED",deliveryState(wait));
        assertEquals(0,acknowledgementAudits(row));
        restoreCheckpoint(row,p.getProperty("application"),wait,message);
        assertEquals("ACKNOWLEDGED",deliveryState(wait));assertEquals(1,acknowledgementAudits(row));
    }
}
