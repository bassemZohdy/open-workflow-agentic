package io.github.bassemzohdy.openworkflow.agentic.contracts.event;

import static org.junit.jupiter.api.Assertions.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.UlidGenerator;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class InboxWaitTest {
    @Test void restoredOccurrenceKeepsItsKeyWhileAnotherIterationAndPinnedDefinitionDoNot() {
        var id = UlidGenerator.shared().next();
        var first = new InboxWait("tenant",id,"app","engine","ns","flow","1","/do/0/wait",1,
                "orders","order.ready","a".repeat(64),Duration.ofMinutes(5));
        assertEquals(first.key(), new InboxWait("tenant",id,"app","engine","ns","flow","1","/do/0/wait",1,
                "orders","order.ready","a".repeat(64),Duration.ofMinutes(5)).key());
        assertNotEquals(first.key(), new InboxWait("tenant",id,"app","engine","ns","flow","1","/do/0/wait",2,
                "orders","order.ready","a".repeat(64),Duration.ofMinutes(5)).key());
        assertNotEquals(first.key(), new InboxWait("tenant",id,"app","engine","ns","flow","2","/do/0/wait",1,
                "orders","order.ready","a".repeat(64),Duration.ofMinutes(5)).key());
        assertNotEquals(first.key(), new InboxWait("tenant",id,"app","engine","ns","flow","1","/do/0/wait",1,
                "orders","order.ready","b".repeat(64),Duration.ofMinutes(5)).key());
    }

    @Test void tenantAndFieldBoundariesCannotAliasAnOccurrence() {
        var id = UlidGenerator.shared().next();
        var a = new InboxWait("a|b",id,"c","engine","ns","flow","1","/wait",1,
                "orders","order.ready","a".repeat(64),Duration.ofSeconds(3));
        var b = new InboxWait("a",id,"b|c","engine","ns","flow","1","/wait",1,
                "orders","order.ready","a".repeat(64),Duration.ofSeconds(3));
        assertNotEquals(a.key(), b.key());
        assertTrue(a.key().length() <= 161);
    }
}
