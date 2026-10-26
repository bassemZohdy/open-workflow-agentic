package io.github.bassemzohdy.openworkflow.agentic.lifecycle;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import io.github.bassemzohdy.openworkflow.agentic.testkit.process.ManagedProcess;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

/** Actual packaged startup, with private databases and a counting local model endpoint. */
class ResearchCheckpointStartupIT {
    private static final Path JAR=Path.of("target/quarkus-app/quarkus-run.jar");
    private static final Path LOGS=Path.of("target/process-logs");

    @Test void freshProcessMigratesBeforeStartupProfileVerificationWithoutRunningTheGraph() throws Exception {
        try(var db=new Database();var model=new ModelProbe();var app=app(db,model,Map.of())) {
            app.start();assertTrue(app.awaitReady(Duration.ofSeconds(60)),read(app));
            assertEquals(1,scalar(db.owner,"select count(*) from public.flyway_schema_history where version='75.1' and success"));
            assertEquals(0,scalar(db.owner,"select count(*) from public.lg4jthread"));
            assertEquals(0,scalar(db.owner,"select count(*) from public.lg4jcheckpoint"));
            assertEquals(0,model.calls.get(),"boot must not call the model");
        }
    }

    @Test void currentMigrationHistoryDoesNotPermitDriftOrDecodeRetainedPrivateStateAtStartup() throws Exception {
        try(var db=new Database();var model=new ModelProbe()) {
            db.migrate();String privateText="checkpoint-private-text-"+UUID.randomUUID();
            seed(db.owner,privateText);
            execute(db.owner,"alter table public.lg4jthread alter column is_released set default true");
            var before=snapshot(db.owner);long history=scalar(db.owner,"select count(*) from public.flyway_schema_history");
            try(var app=app(db,model,Map.of())) {refused(app);assertFalse(read(app).contains(privateText));}
            assertEquals(before,snapshot(db.owner));
            assertEquals(history,scalar(db.owner,"select count(*) from public.flyway_schema_history"));
            assertEquals(0,model.calls.get());
        }
    }

    @Test void currentHistoryWithMissingTablesRefusesWithoutRuntimeCreation() throws Exception {
        try(var db=new Database();var model=new ModelProbe()) {
            db.migrate();execute(db.owner,"drop table public.lg4jcheckpoint; drop table public.lg4jthread");
            long history=scalar(db.owner,"select count(*) from public.flyway_schema_history");
            try(var app=app(db,model,Map.of())) {refused(app);}
            assertEquals(0,scalar(db.owner,"select count(*) from pg_catalog.pg_class where oid in (to_regclass('public.lg4jthread'),to_regclass('public.lg4jcheckpoint'))"));
            assertEquals(history,scalar(db.owner,"select count(*) from public.flyway_schema_history"));
            assertEquals(0,model.calls.get());
        }
    }

    @Test void unsupportedConfiguredNamespaceRefusesAtStartupWithoutFallback() throws Exception {
        try(var db=new Database();var model=new ModelProbe()) {
            db.migrate();seed(db.owner,"owned synthetic history");var before=snapshot(db.owner);
            try(var app=app(db,model,Map.of("agentic.agent.checkpoint-schema","unsupported_checkpoint_target"))) {refused(app);}
            assertEquals(before,snapshot(db.owner));
            assertEquals(0,scalar(db.owner,"select count(*) from pg_catalog.pg_namespace where nspname='unsupported_checkpoint_target'"));
            assertEquals(0,model.calls.get());
        }
    }

    private static void refused(ManagedProcess app) throws Exception {
        app.start();boolean ready=app.awaitReady(Duration.ofSeconds(45));
        assertFalse(ready,"incompatible current profile must refuse before HTTP readiness\n"+read(app));
        assertFalse(app.isAlive(),"startup must exit on profile refusal, rather than hang until the readiness deadline\n"+read(app));
        assertTrue(read(app).contains("CheckpointProfileException"),"must fail for the intended current-profile check\n"+read(app));
    }
    private static String read(ManagedProcess app) throws Exception {return Files.readString(app.logFile());}
    private static ManagedProcess app(Database db,ModelProbe model,Map<String,String> extra) throws Exception {
        assertTrue(Files.isRegularFile(JAR),"package the application before running this process test");
        Files.createDirectories(LOGS);
        var builder=ManagedProcess.jar(JAR).httpPort(ManagedProcess.freePort())
                .logFile(LOGS.resolve("checkpoint-startup-"+UUID.randomUUID()+".log"))
                .property("quarkus.datasource.jdbc.url",db.owner.getURL())
                .property("quarkus.datasource.username",Database.USER)
                .property("quarkus.datasource.password",Database.PASSWORD)
                .property("quarkus.langchain4j.openai.base-url",model.url())
                .property("quarkus.langchain4j.openai.api-key","checkpoint-disposable-model-only")
                .property("agentic.reconciler.enabled","false");
        extra.forEach(builder::property);return builder.build();
    }
    private static void seed(DataSource data,String privateText) throws Exception {
        UUID thread=UUID.randomUUID();
        try(var c=data.getConnection();var q=c.prepareStatement("insert into public.lg4jthread(thread_id,thread_name) values(?,?)")) {
            q.setObject(1,thread);q.setString(2,"owned-startup-"+UUID.randomUUID());q.executeUpdate();
        }
        try(var c=data.getConnection();var q=c.prepareStatement("insert into public.lg4jcheckpoint(checkpoint_id,thread_id,state_data,state_content_type) values(?,?,?,?)")) {
            q.setObject(1,UUID.randomUUID());q.setObject(2,thread);q.setString(3,privateText);
            q.setString(4,"application/octet-stream");q.executeUpdate();
        }
    }
    private static List<String> snapshot(DataSource data) throws Exception {
        var rows=new ArrayList<String>();
        try(var c=data.getConnection();var q=c.createStatement()) {
            for(String table:List.of("lg4jthread","lg4jcheckpoint"))
                try(var r=q.executeQuery("select row_to_json(t)::text from public."+table+" t order by 1")) {
                    while(r.next())rows.add(table+":"+r.getString(1));
                }
        }
        return rows;
    }
    private static void execute(DataSource data,String sql) throws SQLException {
        try(var c=data.getConnection();var q=c.createStatement()){q.setQueryTimeout(10);q.execute(sql);}
    }
    private static long scalar(DataSource data,String sql) throws SQLException {
        try(var c=data.getConnection();var q=c.createStatement()){q.setQueryTimeout(10);try(var r=q.executeQuery(sql)){assertTrue(r.next());return r.getLong(1);}}
    }
    private static final class ModelProbe implements AutoCloseable {
        final AtomicInteger calls=new AtomicInteger();final HttpServer server;
        ModelProbe() throws Exception {
            server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.createContext("/",exchange->{calls.incrementAndGet();exchange.sendResponseHeaders(503,-1);exchange.close();});server.start();
        }
        String url(){return "http://127.0.0.1:"+server.getAddress().getPort()+"/v1/";}
        public void close(){server.stop(0);}
    }
    private static final class Database implements AutoCloseable {
        static final String USER=System.getenv().getOrDefault("DB_USER","postgres");
        static final String PASSWORD=System.getenv().getOrDefault("DB_PASSWORD","postgres");
        final String name="oa_ck_startup_"+UUID.randomUUID().toString().replace("-","");final PGSimpleDataSource owner;
        Database() throws SQLException {assertTrue(name.matches("oa_ck_startup_[0-9a-f]{32}"));execute(data("postgres"),"create database "+name);owner=data(name);}
        void migrate(){Flyway.configure().dataSource(owner).locations("classpath:db/migration").load().migrate();}
        public void close() throws SQLException {assertTrue(name.matches("oa_ck_startup_[0-9a-f]{32}"));execute(data("postgres"),"drop database "+name);}
        private static PGSimpleDataSource data(String database){
            var ds=new PGSimpleDataSource();ds.setURL(System.getenv().getOrDefault("DB_URL","jdbc:postgresql://localhost:5432/agentic"));
            ds.setDatabaseName(database);ds.setUser(USER);ds.setPassword(PASSWORD);return ds;
        }
    }
}
