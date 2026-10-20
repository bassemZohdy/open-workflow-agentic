package io.github.bassemzohdy.openworkflow.agentic.contracts.governance;

import static org.junit.jupiter.api.Assertions.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.common.ThreadId;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.UlidGenerator;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class EffectScopeTest {
    @Test void preservesExistingEffectKeysAcrossNewObjectsAndRecovery() {
        var scope=new EffectScope("tenant",UlidGenerator.shared().next(),new Subject(SubjectKind.USER,"alice"));
        var existing=EffectCommand.of(ThreadId.derive(scope.tenant(),scope.owner().id(),scope.instanceId().value()),
                "charge","loop/7","payments","amount=10");
        assertEquals(existing,scope.command("charge","loop/7","payments","amount=10"));
        var restored=new EffectScope(scope.tenant(),scope.instanceId(),scope.owner());
        assertEquals(existing,restored.command("charge","loop/7","payments","amount=10"));
        assertDoesNotThrow(()->restored.requireKey(existing.key()));
    }
    @Test void anotherTenantOwnerOrRegistryInstanceCannotReuseTheScopedKey() {
        var scope=new EffectScope("tenant",UlidGenerator.shared().next(),new Subject(SubjectKind.USER,"alice"));
        var key=scope.command("charge","1","payments","amount=10").key();
        for(var other:List.of(new EffectScope("other",scope.instanceId(),scope.owner()),
                new EffectScope(scope.tenant(),scope.instanceId(),new Subject(SubjectKind.USER,"bob")),
                new EffectScope(scope.tenant(),UlidGenerator.shared().next(),scope.owner()))) {
            assertNotEquals(scope.threadId(),other.threadId());
            assertThrows(IllegalArgumentException.class,()->other.requireKey(key));
        }
    }
    @Test void carriesTheFullSubjectWithoutChangingTheHistoricalThreadDerivation() {
        var instance=UlidGenerator.shared().next();
        var user=new EffectScope("tenant",instance,new Subject(SubjectKind.USER,"same-id"));
        var service=new EffectScope("tenant",instance,new Subject(SubjectKind.SERVICE,"same-id"));
        assertNotEquals(user,service);
        assertEquals(user.threadId(),service.threadId(),"ws1 did not include subject kind; do not silently change existing keys");
        assertNotEquals(user.owner(),service.owner(),"registry consumers must compare the full Subject, not only a derived hash");
    }
    @Test void retainsExactUnicodeAndRejectsInvalidScopeBeforeDerivingAKey() {
        var instance=UlidGenerator.shared().next();var owner=new Subject(SubjectKind.USER,"alice");
        assertNotEquals(new EffectScope("é",instance,owner).threadId(),new EffectScope("e\u0301",instance,owner).threadId());
        assertThrows(IllegalArgumentException.class,()->new EffectScope(" ",instance,owner));
        assertThrows(NullPointerException.class,()->new EffectScope("tenant",null,owner));
        assertThrows(NullPointerException.class,()->new EffectScope("tenant",instance,null));
    }
}
