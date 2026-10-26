package io.github.bassemzohdy.openworkflow.agentic.lifecycle;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bassemzohdy.openworkflow.agentic.runtime.agent.ResearchState;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;
import javax.sql.DataSource;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.checkpoint.Checkpoint;
import org.bsc.langgraph4j.checkpoint.PostgresSaver;
import org.bsc.langgraph4j.serializer.std.ObjectStreamStateSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

/** Actual V75.1 SQL boundary cases in an isolated database/owned namespaces; no model or authority activation. */
class ResearchCheckpointSchemaTest {
    static final String MIGRATION="db/migration/V75_1__research_checkpoint_profile.sql";
    static final String PINNED="db/migration/v1.1__init.sql";
    static final String PINNED_SHA="f25ce808c4bf2dcca9bd23be68fcdbc374068721a0901477c886ba85e116552d";
    static final List<Map<String,Object>> RESULTS=Collections.synchronizedList(new ArrayList<>());
    static DataSource data;
    static final String password=System.getenv().getOrDefault("DB_PASSWORD","postgres");
    static final String DATABASE="oa_ck_profile_"+UUID.randomUUID().toString().replace("-","");
    static boolean created;
    static DataSource database(String name) {
        var ds=new PGSimpleDataSource();
        ds.setURL(System.getenv().getOrDefault("DB_URL","jdbc:postgresql://localhost:5432/agentic"));
        ds.setDatabaseName(name);ds.setUser(System.getenv().getOrDefault("DB_USER","postgres"));ds.setPassword(password);
        return ds;
    }
    @BeforeAll static void createDatabase() throws Exception {
        try(var c=database("postgres").getConnection()) {sql(c,"create database "+DATABASE);created=true;}
        data=database(DATABASE);
    }
    @FunctionalInterface interface Work {void run(Connection c,String schema) throws Exception;}
    @FunctionalInterface interface CommittedWork {void run(String schema,DataSource scoped) throws Exception;}

    static String resource(String name) throws Exception {
        try(var in=ResearchCheckpointSchemaTest.class.getClassLoader().getResourceAsStream(name)) {
            assertNotNull(in,name);return new String(in.readAllBytes(),StandardCharsets.UTF_8);
        }
    }
    static String sha(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }
    /** Shared fixture bytes also drive the read-only current-profile verifier's parity cases. */
    static Map<String,String> mutations(String group) throws Exception {
        var root=new ObjectMapper().readTree(resource("checkpoint-profile/mutations.json"));
        var node=root.path("groups").path(group);
        assertTrue(node.isObject() && !node.isEmpty(),"missing checkpoint mutation group: "+group);
        var cases=new LinkedHashMap<String,String>();
        var fields=node.fields();
        while(fields.hasNext()) {
            var entry=fields.next();assertTrue(entry.getValue().isTextual());
            cases.put(entry.getKey(),entry.getValue().textValue());
        }
        return cases;
    }
    static void sql(Connection c,String text) throws SQLException {try(var s=c.createStatement()){s.setQueryTimeout(10);s.execute(text);}}
    static String name() {return "gpt_ck_"+UUID.randomUUID().toString().replace("-","");}
    static void target(Connection c,String schema) throws SQLException {
        assertTrue(schema.matches("gpt_ck_[0-9a-f]{32}(?:_fallback)?"));
        sql(c,"set local search_path to "+schema);
    }
    static void vendor(Connection c) throws Exception {
        String source=resource(PINNED);assertEquals(PINNED_SHA,sha(source));sql(c,source);
    }
    static void install(Connection c,String schema) throws Exception {
        String source=resource(MIGRATION),needle="expected_schema constant text := 'public';";
        assertEquals(1,source.split(java.util.regex.Pattern.quote(needle),-1).length-1);
        assertTrue(schema.matches("gpt_ck_[0-9a-f]{32}"));
        sql(c,source.replace(needle,"expected_schema constant text := '"+schema+"';"));
    }
    void fixture(boolean legacy,Work work) throws Exception {
        String s=name();
        try(var c=data.getConnection()) {
            c.setAutoCommit(false);
            try {sql(c,"create schema "+s);target(c,s);if(legacy)vendor(c);work.run(c,s);}
            finally {c.rollback();}
        }
    }
    static List<String> snapshot(Connection c,String schema) throws SQLException {
        var rows=new ArrayList<String>();
        for(String table:List.of("lg4jthread","lg4jcheckpoint")) {
            try(var s=c.prepareStatement("select to_regclass(?) is not null")) {
                s.setString(1,schema+"."+table);
                try(var r=s.executeQuery()) {r.next();if(!r.getBoolean(1)){rows.add(table+":MISSING");continue;}}
            }
            try(var s=c.createStatement();var r=s.executeQuery("select row_to_json(t)::text from "+schema+"."+table+" t order by 1")) {
                while(r.next())rows.add(table+":"+r.getString(1));
            }
        }
        return rows;
    }
    static List<String> constraints(Connection c,String schema) throws SQLException {
        var result=new ArrayList<String>();
        try(var s=c.prepareStatement("select oid::text||':'||pg_get_constraintdef(oid) from pg_constraint where connamespace=?::regnamespace order by oid")) {
            s.setString(1,schema);try(var r=s.executeQuery()){while(r.next())result.add(r.getString(1));}
        }
        return result;
    }
    static void seed(Connection c,String s) throws SQLException {
        UUID thread=UUID.randomUUID();
        try(var q=c.prepareStatement("insert into "+s+".lg4jthread values(?, 'owned-case',false)")) {
            q.setObject(1,thread);q.executeUpdate();
        }
        try(var q=c.prepareStatement("insert into "+s+".lg4jcheckpoint values(?,?,?,'__START__','plan',?,'application/octet-stream',?::timestamptz)")) {
            q.setObject(1,UUID.randomUUID());q.setObject(2,UUID.randomUUID());q.setObject(3,thread);
            q.setString(4,"owned synthetic private text Δ, intentionally not interpreted");
            q.setString(5,"2026-01-01T00:00:00.123456Z");q.executeUpdate();
        }
    }
    static void record(String id,String state,List<String> before,List<String> after) throws Exception {
        assertEquals(before,after);RESULTS.add(Map.of("case",id,"sqlState",state,"historyUnchanged",true,
                "beforeSha256",sha(String.join("\n",before)),"afterSha256",sha(String.join("\n",after))));
    }
    static void refused(Connection c,String s,String state,String id) throws Exception {
        var before=snapshot(c,s);var metadata=constraints(c,s);var point=c.setSavepoint();
        SQLException failure=assertThrows(SQLException.class,()->install(c,s));assertEquals(state,failure.getSQLState());
        c.rollback(point);c.releaseSavepoint(point);assertEquals(metadata,constraints(c,s));record(id,state,before,snapshot(c,s));
    }

    @Test void freshProfileMatchesPinnedResourcesAndRepeatedAdoptionKeepsIdentity() throws Exception {
        fixture(false,(c,s)->{install(c,s);var keys=constraints(c,s);install(c,s);
            assertEquals(keys,constraints(c,s));assertEquals(3,keys.size());record("fresh-idempotent","00000",snapshot(c,s),snapshot(c,s));});
    }
    @Test void compatibleLegacyHistoryRetainsPrivateTextPhysicalIdsAndConstraintObjects() throws Exception {
        fixture(true,(c,s)->{seed(c,s);
            sql(c,"insert into "+s+".lg4jthread values(gen_random_uuid(),'old-released',true),(gen_random_uuid(),'old-released',true)");
            var before=snapshot(c,s);var keys=constraints(c,s);install(c,s);assertEquals(keys,constraints(c,s));
            record("legacy-preserved","00000",before,snapshot(c,s));});
    }
    @Test void partialProfileIsRefusedWithoutCompletingOrResettingIt() throws Exception {
        fixture(false,(c,s)->{sql(c,"create table "+s+".lg4jthread(thread_id uuid primary key,thread_name varchar(255),is_released boolean default false not null)");
            sql(c,"insert into "+s+".lg4jthread values(gen_random_uuid(),'partial-owned',false)");
            refused(c,s,"23514","partial-pair");});
    }
    @Test void incompatibleColumnsDefaultsAndKeysRefuseWithoutRepairingHistory() throws Exception {
        Map<String,String> cases=mutations("columns-and-keys");
        for(var entry:cases.entrySet())fixture(true,(c,s)->{seed(c,s);sql(c,entry.getValue().formatted(s));refused(c,s,"23514",entry.getKey());});
    }
    @Test void requiredIndexSemanticsAndAdditionalUniquenessCannotBeSilentlyAdopted() throws Exception {
        Map<String,String> cases=mutations("indexes");
        for(var entry:cases.entrySet())fixture(true,(c,s)->{seed(c,s);sql(c,entry.getValue().formatted(s));refused(c,s,"23514",entry.getKey());});
    }
    @Test void shadowedNamespacesAndNonTableRelationsRefuseBeforeAdoption() throws Exception {
        fixture(false,(c,s)->{String fallback=s+"_fallback";sql(c,"create schema "+fallback);target(c,fallback);vendor(c);seed(c,fallback);
            var before=snapshot(c,fallback);sql(c,"set local search_path to "+s+","+fallback);
            refused(c,s,"23514","namespace-shadow");assertEquals(before,snapshot(c,fallback));});
        fixture(false,(c,s)->{sql(c,"create view "+s+".lg4jthread as select 'owned'::text as thread_name");
            sql(c,"create table "+s+".lg4jcheckpoint(checkpoint_id uuid)");refused(c,s,"23514","view");});
    }
    @Test void policyTriggersAndChecksOutsideThePinnedProfileAreNotRemovedOrBypassed() throws Exception {
        Map<String,String> cases=mutations("policies");
        for(var entry:cases.entrySet())fixture(true,(c,s)->{seed(c,s);sql(c,entry.getValue().formatted(s));refused(c,s,"23514",entry.getKey());});
    }

    @Test void inheritedParentsAndDescendantsCannotExtendTheCheckpointQueryProfile() throws Exception {
        fixture(true,(c,s)->{seed(c,s);
            sql(c,"create table "+s+".checkpoint_descendant () inherits ("+s+".lg4jcheckpoint)");
            sql(c,"insert into "+s+".checkpoint_descendant select gen_random_uuid(),parent_checkpoint_id,thread_id,node_id,next_node_id,state_data,state_content_type,saved_at from "+s+".lg4jcheckpoint limit 1");
            try(var q=c.createStatement();var r=q.executeQuery("select count(*) from "+s+".lg4jcheckpoint")) {
                assertTrue(r.next());assertEquals(2,r.getInt(1),"ordinary saver queries include descendant rows");
            }
            refused(c,s,"23514","inheritance-parent");});
        fixture(true,(c,s)->{seed(c,s);
            sql(c,"create table "+s+".checkpoint_ancestor(parent_checkpoint_id uuid)");
            sql(c,"alter table "+s+".lg4jcheckpoint inherit "+s+".checkpoint_ancestor");
            refused(c,s,"23514","inheritance-child");});
    }

    void committed(CommittedWork work) throws Exception {
        String schema=name();boolean created=false;
        try {
            var scoped=new PGSimpleDataSource();
            try(var c=data.getConnection()) {
                scoped.setURL(c.getMetaData().getURL());scoped.setUser(c.getMetaData().getUserName());scoped.setPassword(password);scoped.setCurrentSchema(schema);
                sql(c,"create schema "+schema);created=true;c.setAutoCommit(false);
                try {target(c,schema);install(c,schema);c.commit();}finally{c.rollback();}
            }
            work.run(schema,scoped);
        } finally {
            if(created)try(var c=data.getConnection();var q=c.prepareStatement("select nspowner=(select usesysid from pg_user where usename=current_user) from pg_namespace where nspname=?")) {
                assertTrue(schema.matches("gpt_ck_[0-9a-f]{32}"));q.setString(1,schema);
                try(var r=q.executeQuery()){assertTrue(r.next());assertTrue(r.getBoolean(1),"cleanup must own its namespace");}
                sql(c,"drop schema "+schema+" cascade");
            }
        }
    }
    static PostgresSaver saver(DataSource data) throws Exception {
        return PostgresSaver.builder().datasource(data).createTables(false)
                .stateSerializer(new ObjectStreamStateSerializer<>(ResearchState::new)).build();
    }
    static void adoptCommitted(DataSource data,String schema) throws Exception {
        try(var c=data.getConnection()) {c.setAutoCommit(false);try {target(c,schema);install(c,schema);c.commit();}finally{c.rollback();}}
    }
    @Test void actualPinnedSaverStillInsertsReadsUpdatesAndReleasesWithoutCreatingTables() throws Exception {
        committed((s,ds)->{
            var first=saver(ds);var config=RunnableConfig.builder().threadId("owned-"+UUID.randomUUID()).build();
            var original=Checkpoint.builder().state(Map.of(ResearchState.TOPIC,"owned",ResearchState.PLAN,"first"))
                    .nodeId("__START__").nextNodeId("plan").build();
            var stored=first.put(config,original);List<String> before;
            try(var c=ds.getConnection()){before=snapshot(c,s);}
            adoptCommitted(ds,s);
            try(var c=ds.getConnection()){assertEquals(before,snapshot(c,s));}
            assertEquals(original.id(),saver(ds).get(config).orElseThrow().id());
            var replacement=Checkpoint.builder().state(Map.of(ResearchState.TOPIC,"owned",ResearchState.PLAN,"second"))
                    .nodeId("plan").nextNodeId("draft").build();
            first.put(stored,replacement);
            var observed=saver(ds).get(config).orElseThrow();assertEquals(replacement.id(),observed.id());assertEquals(replacement.state(),observed.state());
            saver(ds).release(config);assertTrue(saver(ds).get(config).isEmpty());
            try(var c=ds.getConnection();var q=c.createStatement();var r=q.executeQuery("select is_released from "+s+".lg4jthread")) {assertTrue(r.next());assertTrue(r.getBoolean(1));assertFalse(r.next());}
            adoptCommitted(ds,s);
            // Measured saver behavior, NOT permission to re-enter a completed governed invocation.
            saver(ds).put(config,Checkpoint.copyOf(original));
            try(var c=ds.getConnection();var q=c.createStatement();var r=q.executeQuery("select count(*) from "+s+".lg4jthread")) {assertTrue(r.next());assertEquals(2,r.getInt(1));}
            RESULTS.add(Map.of("case","actual-saver","sqlState","00000","actualSaver",true,"createTables",false,
                    "updateChangesCheckpointId",true,"releaseRetainsHistory",true,"reentryCreatesAnotherRun",true));
        });
    }
    @Test void anActiveWriterMakesAdoptionFailImmediatelyWithoutChangingItsHistory() throws Exception {
        committed((s,ds)->{
            try(var held=ds.getConnection()) {
                held.setAutoCommit(false);
                try {
                    sql(held,"select * from "+s+".lg4jthread for update");
                    try(var contender=ds.getConnection()) {
                        contender.setAutoCommit(false);
                        try {refused(contender,s,"55P03","writer-lock");}finally{contender.rollback();}
                    }
                } finally {held.rollback();}
            }
        });
    }
    @AfterAll static void evidence() throws Exception {
        try {
            Set<String> expected=Set.of("fresh-idempotent","legacy-preserved","partial-pair","type","nullability","default","primary-key","foreign-key","extra-column","column-collation",
                    "missing-index","predicate","sort-order","nulls-not-distinct","extra-unique","namespace-shadow","view","row-security","check","trigger","inheritance-parent","inheritance-child","actual-saver","writer-lock");
            var ids=new HashSet<String>();for(var row:RESULTS)assertTrue(ids.add((String)row.get("case")));assertEquals(expected,ids);
            var sorted=RESULTS.stream().sorted(Comparator.comparing(r->(String)r.get("case"))).toList();
            var report=Map.of("scope","Actual V75.1 profile in isolated owned namespaces; no runtime consumer or execution authority",
                    "productSha",Objects.requireNonNullElse(System.getenv("GITHUB_SHA"),"local"),"profile","langgraph4j-postgres-saver-1.9.5-v1.1",
                    "pinnedDdlSha256",PINNED_SHA,"migrationSha256",sha(resource(MIGRATION)),"cases",sorted);
            var path=Path.of("target","conformance","checkpoint-schema","cases.json");Files.createDirectories(path.getParent());
            new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(path.toFile(),report);
        } finally {
            if(created) {
                assertTrue(DATABASE.matches("oa_ck_profile_[0-9a-f]{32}"));
                try(var c=database("postgres").getConnection()) {sql(c,"drop database "+DATABASE);}
            }
        }
    }
}
