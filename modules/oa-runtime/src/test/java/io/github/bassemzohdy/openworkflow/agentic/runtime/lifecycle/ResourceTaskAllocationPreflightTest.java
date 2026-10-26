package io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle;

import static org.junit.jupiter.api.Assertions.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.*;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

class ResourceTaskAllocationPreflightTest {
    static final Ulid ID=Ulid.parse("01ARZ3NDEKTSV4RRFFQ69G5FAV");
    static ResourceTaskAllocation allocation(String namespace,Duration wall) {
        var owner=new Subject(SubjectKind.USER,"alice");
        var scope=new ResourceCallScope("tenant",ID,owner,"app","engine",namespace,"research","1","do/0",1,"root/1", "research","run","a".repeat(64),"b".repeat(64));
        var contract=new AgentTaskContract(ID,"approved request reference",Set.of("tool:a","tool:z"),Set.of(),RiskTier.HIGH,
                new ExecutionLimits(3,wall,10000),owner,ID);
        var profile=new ApprovedModelProfile("profile","1","c".repeat(64),new Subject(SubjectKind.AGENT,"research"),"model",3,1000,Duration.ofSeconds(10,123),100);
        return new ResourceTaskAllocation(scope,null,contract,profile);
    }
    @Test void lossyTaskTimeAndInvalidRegistryDefinitionRefuseBeforeAnyConnection() {
        AtomicInteger calls=new AtomicInteger();
        DataSource data=(DataSource)Proxy.newProxyInstance(DataSource.class.getClassLoader(),new Class<?>[]{DataSource.class},
                (p,m,a)->{calls.incrementAndGet();throw new AssertionError("no I/O allowed");});
        var manager=(jakarta.transaction.TransactionManager)Proxy.newProxyInstance(
                jakarta.transaction.TransactionManager.class.getClassLoader(),new Class<?>[]{jakarta.transaction.TransactionManager.class},
                (p,m,a)->{throw new AssertionError("invalid input must refuse before transaction I/O");});
        var store=new JdbcResourceTaskAllocations(data,manager);
        assertThrows(IllegalArgumentException.class,()->store.allocate(allocation("valid",Duration.ofSeconds(120,1))));
        assertThrows(IllegalArgumentException.class,()->store.allocate(allocation("bad/namespace",Duration.ofSeconds(120))));
        assertThrows(IllegalArgumentException.class,()->store.find(allocation("bad/namespace",Duration.ofSeconds(120)).scope()));
        assertEquals(0,calls.get());
    }
    @Test void retainedCodecPreservesFullContractAndNanosecondModelTime() {
        var a=allocation("valid",Duration.ofSeconds(120));
        assertEquals(a.contract(),ResourceTaskAllocationCodec.contract(ResourceTaskAllocationCodec.contract(a.contract())));
        assertEquals(a.modelProfile(),ResourceTaskAllocationCodec.profile(ResourceTaskAllocationCodec.profile(a.modelProfile())));
    }
    @Test void codecRefusesUnknownFieldsAndNumericCoercionRatherThanDroppingData() {
        var a=allocation("valid",Duration.ofSeconds(120));String c=ResourceTaskAllocationCodec.contract(a.contract());
        String p=ResourceTaskAllocationCodec.profile(a.modelProfile());
        assertThrows(IllegalStateException.class,()->ResourceTaskAllocationCodec.contract(c.substring(0,c.length()-1)+",\"extra\":true}"));
        assertThrows(IllegalStateException.class,()->ResourceTaskAllocationCodec.profile(p.substring(0,p.length()-1)+",\"extra\":true}"));
        assertThrows(IllegalStateException.class,()->ResourceTaskAllocationCodec.contract(c.replace("\"maxIterations\":3","\"maxIterations\":\"3\"")));
        assertThrows(IllegalStateException.class,()->ResourceTaskAllocationCodec.profile(p.replace("\"maxModelCalls\":3","\"maxModelCalls\":3.1")));
    }
    @Test void allowListOrderIsNotIdentityButDuplicatesAndNumericTokensAreNotSilentlyDropped() {
        var a=allocation("valid",Duration.ofSeconds(120));String c=ResourceTaskAllocationCodec.contract(a.contract());
        assertEquals(a.contract(),ResourceTaskAllocationCodec.contract(c.replace("[\"tool:a\",\"tool:z\"]","[\"tool:z\",\"tool:a\"]")));
        assertThrows(IllegalStateException.class,()->ResourceTaskAllocationCodec.contract(c.replace("[\"tool:a\",\"tool:z\"]","[\"tool:a\",\"tool:a\"]")));
        assertThrows(IllegalStateException.class,()->ResourceTaskAllocationCodec.contract(c.replace("[\"tool:a\",\"tool:z\"]","[5,\"tool:z\"]")));
    }
}
