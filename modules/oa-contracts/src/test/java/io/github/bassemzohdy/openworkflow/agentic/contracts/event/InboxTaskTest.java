package io.github.bassemzohdy.openworkflow.agentic.contracts.event;

import static org.junit.jupiter.api.Assertions.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.UlidGenerator;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class InboxTaskTest {
    @Test void registrationsWithDifferentFiltersRoutesTypesAndDurationsShareOnlyTheirTaskOwnership() {
        var id=UlidGenerator.shared().next();
        var a=new InboxWait("tenant",id,"app","engine","ns","flow","1","do/0/wait",1,
                "order","order.ready","a".repeat(64),Duration.ofMinutes(1));
        var b=new InboxWait("tenant",id,"app","engine","ns","flow","1","do/0/wait",1,
                "payment","payment.ready","b".repeat(64),Duration.ofMinutes(2));
        assertNotEquals(a.digest(),b.digest()); assertEquals(a.task(),b.task()); assertEquals(a.task().digest(),b.task().digest());
        assertEquals(a.task(),new InboxTask("tenant",id,"app","engine","ns","flow","1","do/0/wait",1));
    }
    @Test void anotherIterationParallelPositionEngineOrPinnedVersionCannotShareTheFence() {
        var id=UlidGenerator.shared().next(); var a=new InboxTask("t",id,"app","engine","ns","flow","1","do/0/wait",1);
        assertNotEquals(a.digest(),new InboxTask("t",id,"app","engine","ns","flow","1","do/0/wait",2).digest());
        assertNotEquals(a.digest(),new InboxTask("t",id,"app","engine","ns","flow","1","do/1/wait",1).digest());
        assertNotEquals(a.digest(),new InboxTask("t",id,"app","engine-2","ns","flow","1","do/0/wait",1).digest());
        assertNotEquals(a.digest(),new InboxTask("t",id,"app","engine","ns","flow","2","do/0/wait",1).digest());
        assertNotEquals(a.digest(),new InboxTask("t",id,"app","engine","ns","flow","1","/do/0/wait",1).digest());
        assertThrows(IllegalArgumentException.class,()->new InboxTaskClaim(a,"owner",0));
        assertThrows(IllegalArgumentException.class,()->new InboxTaskClaim(a,"bad owner",1));
    }
}
