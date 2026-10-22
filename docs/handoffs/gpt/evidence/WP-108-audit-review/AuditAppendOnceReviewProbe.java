package io.github.bassemzohdy.openworkflow.agentic.runtime.audit;

import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.UlidGenerator;
import java.lang.reflect.Proxy;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import javax.sql.DataSource;

/** GPT-owned ignored probe. Pure JDBC proxy checks, not real PostgreSQL execution. */
public class AuditAppendOnceReviewProbe {
    static boolean opened, committed, secondAcquireWhileFirstOpen;
    static Object zero(Class<?> type) {
        if(type==boolean.class)return false;
        if(type==int.class)return 0;
        if(type==long.class)return 0L;
        return null;
    }
    static Connection connection() {
        return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class[]{Connection.class},(proxy,method,args)-> {
            if(method.getName().equals("close")){opened=false;return null;}
            if(method.getName().equals("commit")){committed=true;throw new SQLException("synthetic lost commit reply","08006");}
            if(method.getName().equals("prepareStatement"))return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),new Class[]{PreparedStatement.class},(p,m,a)-> {
                if(m.getName().equals("executeQuery"))return Proxy.newProxyInstance(ResultSet.class.getClassLoader(),new Class[]{ResultSet.class},(r,rm,ra)->zero(rm.getReturnType()));
                if(m.getName().equals("executeUpdate"))return 1;
                return zero(m.getReturnType());
            });
            return zero(method.getReturnType());
        });
    }
    static AuditEvent draft(String chain) {
        return AuditDraft.of(UlidGenerator.shared().next(),Instant.parse("2026-10-21T08:00:00.123456Z"),
                new Subject(SubjectKind.SERVICE,"review-producer"),"effect.dispatch","effect","synthetic",Map.of("oa.chain",chain));
    }
    static AuditSource source(String key,AuditEvent draft) {
        return new AuditSource(1,"a".repeat(64),key.repeat(64),AuditSource.Transition.INTENT,1,AuditDraftFingerprint.of(draft));
    }
    public static void main(String[] args) {
        DataSource oneConnection=(DataSource)Proxy.newProxyInstance(DataSource.class.getClassLoader(),new Class[]{DataSource.class},(p,m,a)-> {
            if(m.getName().equals("getConnection")) {
                if(opened){secondAcquireWhileFirstOpen=true;throw new SQLException("one-slot pool is still checked out","08001");}
                opened=true;return connection();
            }
            return zero(m.getReturnType());
        });
        AuditEvent draft=draft("review");
        try {new AuditAppendOnce(oneConnection).appendOnce(source("b",draft),draft);throw new AssertionError("Expected pool exhaustion");}
        catch(AuditCommitUncertainException expected) {
            if(!committed || !secondAcquireWhileFirstOpen || opened)throw new AssertionError("Unexpected lifecycle");
            System.out.println("CONFIRMED_JDBC_ORDER: commit reached; readback asks for connection2 while connection1 remains open; bounded pool refuses; original closes only afterward");
        }
        AuditEvent dummy=draft("default");AuditSource a=source("b",dummy),b=source("c",dummy);
        AuditEvent ad=draft("oa.audit.source:"+b.key()),bd=draft("oa.audit.source:"+a.key());
        AuditSource actualA=source("b",ad),actualB=source("c",bd);
        if(!a.key().equals(actualA.key()) || !b.key().equals(actualB.key()))throw new AssertionError("Identity changed");
        String aSource="oa.audit.source:"+actualA.key(),bSource="oa.audit.source:"+actualB.key();
        if(!aSource.equals(AuditDraftFingerprint.chainOf(bd)) || !bSource.equals(AuditDraftFingerprint.chainOf(ad)))throw new AssertionError("No alias");
        System.out.println("CONFIRMED_VALID_LOCK_ARGUMENT_ALIAS: A.sourceLock==B.chainLock and B.sourceLock==A.chainLock; both use the same bigint hashtextextended advisory-lock function");
        System.out.println("SCOPE: real AuditAppendOnce control flow plus JDBC proxies/valid contract inputs; no real database commit or PostgreSQL deadlock execution claimed");
    }
}
