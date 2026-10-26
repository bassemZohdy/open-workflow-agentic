package io.github.bassemzohdy.openworkflow.agentic.lifecycle;

import static org.junit.jupiter.api.Assertions.*;
import static io.github.bassemzohdy.openworkflow.agentic.lifecycle.ResourceExecutionMigrationTest.*;

import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.ResourceCallBinding;
import io.github.bassemzohdy.openworkflow.agentic.contracts.port.ResourceInvocations;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.IssuedTokenBindings;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.JdbcIssuedTokenBindings;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.JdbcResourceCallBindings;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.StoreUnavailableException;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/** Canonical historical DATA only; no credential, fingerprint or returned row grants permission. */
class ResourceCredentialLookupTest {
    @Test void exactCredentialsAreSeparateEvenForTheSameCallOrSameJtiAndRemainHistoricalAfterCompletion() throws Exception {
        try(var db=new Database()) {
            db.migrate(null);var f=fixture(db,"a".repeat(64));var b=f.binding();
            ResourceInvocations store=new JdbcResourceCallBindings(db.owner);
            String issuer="https://another-issuer.test";
            new JdbcIssuedTokenBindings(db.owner).bind(issuer,b.tokenId(),new IssuedTokenBindings.Binding(
                    b.issuingGrantId(),b.scope().tenant(),b.expiresAt()));
            var sameJti=store.bind(new ResourceCallBinding(issuer,b.tokenId(),b.issuingGrantId(),b.scope(),b.issuedAt(),b.expiresAt()));
            var now=databaseNow(db.owner);var refreshed=credential(db,f,now.minusSeconds(1),now.plusSeconds(120));
            assertNotEquals(b.key(),sameJti.key());assertNotEquals(b.key(),refreshed.key());
            for(var binding:new ResourceCallBinding[]{b,sameJti,refreshed})assertEquals(binding,store.findBinding(binding.key()).orElseThrow());
            assertTrue(store.findBinding("rb1-"+"0".repeat(64)).isEmpty());
            execute(db.owner,"update task.instance set status='COMPLETED' where id='"+f.row().id()+"'");
            assertEquals(b,store.findBinding(b.key()).orElseThrow());
            assertEquals(1,count(db.owner,"select count(*) from task.resource_call"));
        }
    }
    @Test void malformedFingerprintsRefuseBeforeAnyDatabaseAcquisition() {
        var calls=new AtomicInteger();
        DataSource forbidden=(DataSource)Proxy.newProxyInstance(DataSource.class.getClassLoader(),new Class<?>[]{DataSource.class},
                (p,m,a)->{calls.incrementAndGet();throw new AssertionError("malformed key reached data source");});
        ResourceInvocations store=new JdbcResourceCallBindings(forbidden);
        for(String key:new String[]{"","rb1-"+"a".repeat(63),"rb1-"+"a".repeat(65),"rb1-"+"A".repeat(64),
                "rc1-"+"a".repeat(64)," rb1-"+"a".repeat(64),"rb1-"+"a".repeat(64)+"\n","rb1-\uD800"})
            assertThrows(IllegalArgumentException.class,()->store.findBinding(key));
        assertThrows(NullPointerException.class,()->store.findBinding(null));assertEquals(0,calls.get());
    }
    @Test void aMissingCanonicalOccurrenceRefusesInsteadOfReturningAbsentOrAnAlternateCredential() throws Exception {
        try(var db=new Database()) {
            db.migrate(null);var f=fixture(db,"a".repeat(64));ResourceInvocations store=new JdbcResourceCallBindings(db.owner);
            assertEquals(f.binding(),store.findBinding(f.binding().key()).orElseThrow());
            execute(db.owner,"alter table task.resource_occurrence disable trigger user");
            execute(db.owner,"delete from task.resource_occurrence");
            execute(db.owner,"alter table task.resource_occurrence enable trigger user");
            assertThrows(IllegalStateException.class,()->store.findBinding(f.binding().key()));
        }
    }
    @Test void aCorruptRetainedCredentialKeyRefusesItsRecomputedIdentity() throws Exception {
        try(var db=new Database()) {
            db.migrate(null);var f=fixture(db,"a".repeat(64));ResourceInvocations store=new JdbcResourceCallBindings(db.owner);
            assertEquals(f.binding(),store.findBinding(f.binding().key()).orElseThrow());
            String wrong="rb1-"+"f".repeat(64);assertNotEquals(wrong,f.binding().key());
            execute(db.owner,"alter table task.resource_call_binding disable trigger user");
            execute(db.owner,"update task.resource_call_binding set binding_key='"+wrong+"'");
            execute(db.owner,"alter table task.resource_call_binding enable trigger user");
            assertThrows(IllegalStateException.class,()->store.findBinding(wrong));
        }
    }
    @Test void anOrphanedBindingIsNotHiddenByAnInnerJoin() throws Exception {
        try(var db=new Database()) {
            db.migrate(null);var f=fixture(db,"a".repeat(64));ResourceInvocations store=new JdbcResourceCallBindings(db.owner);
            assertEquals(f.binding(),store.findBinding(f.binding().key()).orElseThrow());
            // Superuser-only corruption in this owned UUID database, not a production repair or a shared reset.
            execute(db.owner,"alter table task.resource_call disable trigger all");
            execute(db.owner,"delete from task.resource_call");
            execute(db.owner,"alter table task.resource_call enable trigger all");
            assertThrows(IllegalStateException.class,()->store.findBinding(f.binding().key()));
        }
    }
    @Test void anUnavailableSchemaIsNotReportedAsAMissingCredential() throws Exception {
        try(var db=new Database()) {
            db.migrate(null);var f=fixture(db,"a".repeat(64));ResourceInvocations store=new JdbcResourceCallBindings(db.owner);
            assertEquals(f.binding(),store.findBinding(f.binding().key()).orElseThrow());
            execute(db.owner,"alter table task.resource_call_binding rename to unavailable_owned_fixture");
            assertThrows(StoreUnavailableException.class,()->store.findBinding(f.binding().key()));
        }
    }
}
