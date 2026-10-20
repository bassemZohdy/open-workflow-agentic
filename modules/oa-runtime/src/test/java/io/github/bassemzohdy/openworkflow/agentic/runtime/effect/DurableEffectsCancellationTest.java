package io.github.bassemzohdy.openworkflow.agentic.runtime.effect;

import static org.junit.jupiter.api.Assertions.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.common.ThreadId;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.port.EffectTarget;
import java.lang.reflect.Proxy;
import java.time.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/** Deterministic caller-interrupt boundaries, explicit ledger/target doubles. Actual PostgreSQL cases live in the app. */
class DurableEffectsCancellationTest {
    static final Instant NOW=Instant.parse("2026-10-20T07:00:00Z");
    static final EffectCommand COMMAND=EffectCommand.of(ThreadId.derive("test","alice","cancel"),"charge","1","test:payments","amount=10");
    static DataSource noIo(AtomicInteger calls) {
        return (DataSource)Proxy.newProxyInstance(DataSource.class.getClassLoader(),new Class<?>[]{DataSource.class},(p,m,a)->{
            calls.incrementAndGet();throw new AssertionError("this unit fixture must not access JDBC");
        });
    }
    static class Target implements EffectTarget {
        int metadata,dispatch,lookup;
        boolean interruptDispatch,interruptLookup;
        public String target(){metadata++;return "test:payments";}
        public Duration replayWindow(){metadata++;return Duration.ofHours(1);}
        public Reply execute(String key){dispatch++;if(interruptDispatch)Thread.currentThread().interrupt();return new Reply(Outcome.CONFIRMED,"confirmed");}
        public Reply lookup(String key){lookup++;if(interruptLookup)Thread.currentThread().interrupt();return new Reply(Outcome.UNKNOWN,null);}
    }
    static class Ledger extends JdbcEffectLedger {
        boolean cancelClaim,cancelPrepare,cancelAttempt,cancelOutcome,cancelUnlock,attempted;
        int prepares,attempts,outcomes,manual;
        Effect effect;
        Ledger(){super(noIo(new AtomicInteger()));}
        public <T>T exclusively(EffectKey key,Supplier<T> work){
            if(cancelClaim)Thread.currentThread().interrupt();
            try{return work.get();}finally{if(cancelUnlock)Thread.currentThread().interrupt();}
        }
        public Prepared prepare(EffectCommand command,Duration window,Instant now){
            prepares++;
            if(effect==null)effect=new Effect(command.key().threadId(),command.key().nodeId(),command.key().idempotencyKey(),
                    command.target(),EffectState.INTENDED,null,now);
            if(cancelPrepare)Thread.currentThread().interrupt();
            return new Prepared(effect,attempted,NOW.plus(window),NOW);
        }
        public void markAttempted(EffectKey key){attempted=true;attempts++;if(cancelAttempt)Thread.currentThread().interrupt();}
        public Effect recordOutcome(EffectKey key,EffectState state,String detail){
            outcomes++;effect=new Effect(key.threadId(),key.nodeId(),key.idempotencyKey(),COMMAND.target(),state,detail,NOW);
            if(cancelOutcome)Thread.currentThread().interrupt();return effect;
        }
        public void requireManualResolution(EffectKey key,String reason){manual++;}
        public Optional<Effect> find(EffectKey key){return Optional.ofNullable(effect);}
    }
    @Test void alreadyInterruptedExecuteAndCompensationTouchNeitherStoreNorAdapterMetadata() {
        for(boolean compensation:List.of(false,true)) {
            var io=new AtomicInteger();var target=new Target();var effects=new DurableEffects(new JdbcEffectLedger(noIo(io)));
            Thread.currentThread().interrupt();
            try {
                assertThrows(CancellationException.class,()->{
                    if(compensation)effects.compensate(COMMAND.key(),EffectCommand.of(COMMAND.key().threadId(),"refund","1",COMMAND.target(),"refund=10"),target);
                    else effects.execute(COMMAND,target);
                });
                assertTrue(Thread.currentThread().isInterrupted());assertEquals(0,io.get());assertEquals(0,target.metadata);
                assertEquals(0,target.dispatch);assertEquals(0,target.lookup);
            } finally {Thread.interrupted();}
        }
    }
    @Test void cancellationDuringClaimOrIntentWorkNeverMarksOrDispatches() {
        for(boolean duringClaim:List.of(true,false)) {
            var ledger=new Ledger();ledger.cancelClaim=duringClaim;ledger.cancelPrepare=!duringClaim;var target=new Target();
            try {
                assertThrows(CancellationException.class,()->new DurableEffects(ledger,Clock.fixed(NOW,ZoneOffset.UTC)).execute(COMMAND,target));
                assertTrue(Thread.currentThread().isInterrupted());assertEquals(duringClaim?0:1,ledger.prepares);
                assertEquals(0,ledger.attempts);assertEquals(0,target.dispatch);assertEquals(0,target.lookup);
                if(!duringClaim)assertEquals(EffectState.INTENDED,ledger.effect.state());
            } finally {Thread.interrupted();}
        }
    }
    @Test void cancellationAfterAttemptCommitRetainsAttemptAndPreventsTargetEntry() {
        var ledger=new Ledger();ledger.cancelAttempt=true;var target=new Target();
        try {
            assertThrows(CancellationException.class,()->new DurableEffects(ledger,Clock.fixed(NOW,ZoneOffset.UTC)).execute(COMMAND,target));
            assertTrue(Thread.currentThread().isInterrupted());assertTrue(ledger.attempted);
            assertEquals(1,ledger.attempts);assertEquals(0,target.dispatch);assertEquals(EffectState.INTENDED,ledger.effect.state());
        } finally {Thread.interrupted();}
    }
    @Test void interruptionDuringUnknownLookupNeverReplaysOrReturnsProgress() {
        var ledger=new Ledger();ledger.attempted=true;var target=new Target();target.interruptLookup=true;
        try {
            assertThrows(CancellationException.class,()->new DurableEffects(ledger,Clock.fixed(NOW,ZoneOffset.UTC)).execute(COMMAND,target));
            assertTrue(Thread.currentThread().isInterrupted());assertEquals(1,target.lookup);assertEquals(0,target.dispatch);
            assertEquals(0,ledger.manual);assertEquals(0,ledger.outcomes);assertEquals(EffectState.INTENDED,ledger.effect.state());
        } finally {Thread.interrupted();}
    }
    @Test void confirmedFactsAreRetainedButCancellationNeverReturnsThemAsProgress() {
        for(boolean duringTarget:List.of(true,false)) {
            var ledger=new Ledger();ledger.cancelOutcome=!duringTarget;var target=new Target();target.interruptDispatch=duringTarget;
            try {
                assertThrows(CancellationException.class,()->new DurableEffects(ledger,Clock.fixed(NOW,ZoneOffset.UTC)).execute(COMMAND,target));
                assertTrue(Thread.currentThread().isInterrupted());assertEquals(1,target.dispatch);
                assertEquals(1,ledger.outcomes);assertEquals(EffectState.CONFIRMED,ledger.effect.state());
            } finally {Thread.interrupted();}
        }
    }
    @Test void lateClaimOrTraceCleanupCancellationCannotReleaseProgressOrLeakTheWorkerBudget() {
        for(boolean duringUnlock:List.of(true,false)) {
            var ledger=new Ledger();ledger.cancelUnlock=duringUnlock;var target=new Target();
            var cancelTrace=new java.util.concurrent.atomic.AtomicBoolean(!duringUnlock);
            var telemetry=new EffectTelemetry(io.opentelemetry.api.OpenTelemetry.noop()) {
                public Effect effect(Operation operation,Supplier<Effect> work) {
                    Effect result=super.effect(operation,work);
                    if(cancelTrace.get())Thread.currentThread().interrupt();return result;
                }
            };
            var effects=new DurableEffects(ledger,Clock.fixed(NOW,ZoneOffset.UTC),telemetry);
            // More than the four-slot budget: cancelled calls must not leak an acquired slot.
            for(int call=0;call<5;call++) {
                try {
                    assertThrows(CancellationException.class,()->effects.execute(COMMAND,target));
                    assertTrue(Thread.currentThread().isInterrupted());assertEquals(EffectState.CONFIRMED,ledger.effect.state());
                } finally {Thread.interrupted();}
            }
            ledger.cancelUnlock=false;cancelTrace.set(false);
            assertEquals(EffectState.CONFIRMED,effects.execute(COMMAND,target).state());assertEquals(1,target.dispatch);
        }
    }
}
