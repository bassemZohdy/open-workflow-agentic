package io.github.bassemzohdy.openworkflow.agentic.runtime.event;

import static org.junit.jupiter.api.Assertions.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.event.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid;
import java.lang.reflect.Proxy;
import java.time.*;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/** Invalid producer metadata must be refused before any database/audit side effect. */
class ListenGroupValidationTest {
    private static InboxListenGroup group(String snapshot) {
        var task=new InboxTask("tenant",Ulid.parse("01ARZ3NDEKTSV4RRFFQ69G5FAV"),"app","engine","ns","flow","1","do/0/listen",1);
        return new InboxListenGroup(task,InboxListenGroup.Strategy.ONE,List.of("a".repeat(64)),InboxListenGroup.Stop.NONE,List.of(),null,
                InboxListenGroup.snapshotFingerprint(snapshot));
    }
    private static JdbcListenGroups noIo() {
        var source=(DataSource)Proxy.newProxyInstance(DataSource.class.getClassLoader(),new Class<?>[]{DataSource.class},
                (p,m,args)->{throw new AssertionError("invalid metadata must not access the database");});
        io.github.bassemzohdy.openworkflow.agentic.runtime.audit.AuditSink audit=e->{throw new AssertionError("invalid metadata must not write audit");};
        return new JdbcListenGroups(source,new JdbcInboxTaskLease(source,audit,Clock.systemUTC()),audit,Clock.systemUTC());
    }
    @Test void duplicateTrailingAndNonObjectJsonCannotReachStorage() {
        var store=noIo();
        for(String snapshot:List.of("[]","null","{\"scope\":1,\"scope\":2}","{} {}")) {
            var group=group(snapshot);var claim=new InboxTaskClaim(group.task(),"owner",1);
            assertThrows(IllegalArgumentException.class,()->store.register(claim,group,snapshot,Duration.ofSeconds(30)));
        }
    }
    @Test void changedSnapshotAndUnsupportedTimeoutPrecisionCannotReachStorage() {
        var store=noIo();var group=group("{}");var claim=new InboxTaskClaim(group.task(),"owner",1);
        assertThrows(IllegalArgumentException.class,()->store.register(claim,group,"{ }",Duration.ofSeconds(30)));
        for(var timeout:List.of(Duration.ZERO,Duration.ofNanos(1),Duration.ofMillis(1).plusNanos(1),Duration.ofDays(31)))
            assertThrows(IllegalArgumentException.class,()->store.register(claim,group,"{}",timeout));
    }
}
