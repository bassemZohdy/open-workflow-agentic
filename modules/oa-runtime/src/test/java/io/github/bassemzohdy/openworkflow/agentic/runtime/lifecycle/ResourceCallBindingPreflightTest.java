package io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle;

import static org.junit.jupiter.api.Assertions.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.*;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

class ResourceCallBindingPreflightTest {
    private static final Ulid ID=Ulid.parse("01ARZ3NDEKTSV4RRFFQ69G5FAV");
    private static ResourceCallBinding binding(String namespace,Instant issued) {
        return new ResourceCallBinding("issuer","jti",ID,new ResourceCallScope("tenant",ID,
                new Subject(SubjectKind.USER,"alice"),"app","engine",namespace,"workflow","1","do/0",1,
                "loop/1","audience","run","a".repeat(64),"b".repeat(64)),issued,issued.plusSeconds(120));
    }
    @Test void lossyTimestampAndInvalidRegistryDefinitionAreRejectedBeforeAnyConnection() {
        AtomicInteger calls=new AtomicInteger();
        DataSource data=(DataSource)Proxy.newProxyInstance(DataSource.class.getClassLoader(),new Class<?>[]{DataSource.class},
                (p,m,a)->{calls.incrementAndGet();throw new AssertionError("no I/O permitted");});
        var store=new JdbcResourceCallBindings(data);
        assertThrows(IllegalArgumentException.class,()->store.bind(binding("valid",Instant.parse("2026-10-20T00:00:00.000000001Z"))));
        assertThrows(IllegalArgumentException.class,()->store.bind(binding("valid",Instant.parse("+10000-01-01T00:00:00Z"))));
        assertThrows(IllegalArgumentException.class,()->store.bind(binding("invalid/namespace",Instant.parse("2026-10-20T00:00:00Z"))));
        assertThrows(IllegalArgumentException.class,()->store.findInvocation(binding("invalid/namespace",Instant.parse("2026-10-20T00:00:00Z")).scope()));
        assertEquals(0,calls.get());
    }
    @Test void malformedVerifiedIdentityCannotReachStorage() {
        AtomicInteger calls=new AtomicInteger();
        DataSource data=(DataSource)Proxy.newProxyInstance(DataSource.class.getClassLoader(),new Class<?>[]{DataSource.class},
                (p,m,a)->{calls.incrementAndGet();throw new AssertionError("no I/O permitted");});
        var store=new JdbcResourceCallBindings(data);
        assertThrows(IllegalArgumentException.class,()->store.find(" ","jti"));
        assertThrows(IllegalArgumentException.class,()->store.find("issuer","\ud800"));
        assertThrows(IllegalArgumentException.class,()->store.find("issuer","x".repeat(301)));
        assertEquals(0,calls.get());
    }
}
