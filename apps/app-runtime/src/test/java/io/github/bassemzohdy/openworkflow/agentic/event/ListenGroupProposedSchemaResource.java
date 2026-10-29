package io.github.bassemzohdy.openworkflow.agentic.event;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.postgresql.ds.PGSimpleDataSource;

/** Isolates the proposed schema from the default test database and application process tests. */
public class ListenGroupProposedSchemaResource implements QuarkusTestResourceLifecycleManager {
    private final String name="oa_match_component_"+UUID.randomUUID().toString().replace("-","");
    private boolean created;
    private static PGSimpleDataSource data(String name) {
        var ds=new PGSimpleDataSource();ds.setURL(System.getenv().getOrDefault("DB_URL","jdbc:postgresql://localhost:5432/agentic"));
        ds.setDatabaseName(name);ds.setUser(System.getenv().getOrDefault("DB_USER","postgres"));ds.setPassword(System.getenv().getOrDefault("DB_PASSWORD","postgres"));return ds;
    }
    @Override public Map<String,String> start() {
        try {
            try(var c=data("postgres").getConnection();var s=c.createStatement()) {s.execute("create database "+name);created=true;}
            var ds=data(name);Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
            try(var stream=getClass().getResourceAsStream("/db/proposed-listen/V81__listen_complete_match_identity.sql")) {
                if(stream==null)throw new IllegalStateException("proposed match schema resource is missing");
                String sql=new String(stream.readAllBytes(),StandardCharsets.UTF_8);
                try(Connection c=ds.getConnection();var s=c.createStatement()) {s.execute(sql);}
            }
            return Map.of("quarkus.datasource.jdbc.url",ds.getURL());
        } catch(Exception failure) {try{stop();}catch(Exception cleanup){failure.addSuppressed(cleanup);}throw new IllegalStateException(failure);}
    }
    @Override public void stop() {
        if(!created)return;
        if(!name.matches("oa_match_component_[0-9a-f]{32}"))throw new IllegalStateException("unexpected database name");
        try(var c=data("postgres").getConnection();var s=c.createStatement()) {s.execute("drop database "+name);created=false;}
        catch(SQLException failure) {throw new IllegalStateException("cannot drop disposable database",failure);}
    }
}
