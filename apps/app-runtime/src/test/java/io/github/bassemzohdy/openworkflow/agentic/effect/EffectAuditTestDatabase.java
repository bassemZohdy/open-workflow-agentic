package io.github.bassemzohdy.openworkflow.agentic.effect;

import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.postgresql.ds.PGSimpleDataSource;

/** Test-only fresh database ownership. Never baseline/clean an existing store or force/terminate its sessions. */
final class EffectAuditTestDatabase implements AutoCloseable {
    final String name="gpt_eap_"+UUID.randomUUID().toString().replace("-","");
    final PGSimpleDataSource admin=dataSource(null), dataSource=dataSource(name);
    private boolean created;
    static PGSimpleDataSource dataSource(String name) {
        var ds=new PGSimpleDataSource();ds.setUrl(System.getenv().getOrDefault("DB_URL","jdbc:postgresql://localhost:5432/agentic"));
        if(name!=null)ds.setDatabaseName(name);
        ds.setUser(System.getenv().getOrDefault("DB_USER","postgres"));ds.setPassword(System.getenv().getOrDefault("DB_PASSWORD","postgres"));
        ds.setConnectTimeout(5);ds.setSocketTimeout(10);return ds;
    }
    static void requireOwnedDatabase(String name,PGSimpleDataSource ds) throws SQLException {
        if(name==null || !name.matches("gpt_eap_[0-9a-f]{32}"))throw new IllegalArgumentException("invalid owned test database");
        try(var c=ds.getConnection();var q=c.createStatement();var r=q.executeQuery("select current_database()")) {
            if(!r.next() || !name.equals(r.getString(1)))throw new IllegalStateException("test database differs");
        }
    }
    static EffectAuditTestDatabase open() throws Exception {
        var db=new EffectAuditTestDatabase();
        try {
            if(!db.name.matches("gpt_eap_[0-9a-f]{32}"))throw new IllegalStateException("invalid test database");
            try(var c=db.admin.getConnection()){sql(c,"create database "+db.name);db.created=true;}
            requireOwnedDatabase(db.name,db.dataSource);
            Flyway.configure().dataSource(db.dataSource).locations("classpath:db/migration").load().migrate();
            try(var c=db.dataSource.getConnection()) {
                c.setAutoCommit(false);
                try{sql(c,resource("V75__audit_source_binding.sql"));sql(c,resource("V77__retained_effect_audit_sources.sql"));c.commit();}
                finally{c.rollback();}
            }
            return db;
        }catch(Exception|Error failed){try{db.close();}catch(Exception cleanup){failed.addSuppressed(cleanup);}throw failed;}
    }
    static String resource(String name) throws Exception {
        try(var in=EffectAuditTestDatabase.class.getClassLoader().getResourceAsStream("db/proposed/"+name)) {
            if(in==null)throw new IllegalStateException("test proposal missing: "+name);
            return new String(in.readAllBytes(),StandardCharsets.UTF_8);
        }
    }
    static void sql(Connection c,String text) throws SQLException {try(var q=c.createStatement()){q.setQueryTimeout(15);q.execute(text);}}
    public void close() throws SQLException {
        if(!created)return;
        if(!name.matches("gpt_eap_[0-9a-f]{32}"))throw new IllegalStateException("invalid test database");
        // created becomes true ONLY after our CREATE succeeded; no IF EXISTS or FORCE.
        try(var c=admin.getConnection()){sql(c,"drop database "+name);created=false;}
    }
}
