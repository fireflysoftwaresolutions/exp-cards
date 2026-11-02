/* Copyright 2026 Firefly Software Foundation. Licensed under the Apache License, Version 2.0. */
package io.fireflysoftware.qualification.cards;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.sql.DriverManager;
import java.util.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

record CardRuntimeTestDatabase(String host,int port,String name,String user,String password,PostgreSQLContainer owned,String cacheName,String cacheUser,String cachePassword) {
    static CardRuntimeTestDatabase start(PostgreSQLContainer container)throws Exception {
        String profile=System.getProperty("cards.http.test.database-profile");CardRuntimeTestDatabase database;
        String suffix=UUID.randomUUID().toString().replace("-","");String cacheName="cards_http_cache_"+suffix,cacheUser="cards_cache_"+suffix;
        String cachePassword=UUID.randomUUID().toString()+UUID.randomUUID();
        if(profile==null){
            container.withReuse(false).start();database=new CardRuntimeTestDatabase(container.getHost(),container.getMappedPort(5432),container.getDatabaseName(),container.getUsername(),container.getPassword(),container,cacheName,cacheUser,cachePassword);
        }else{
            var path=Path.of(profile);assertTrue(path.isAbsolute()&&!Files.isSymbolicLink(path)&&Files.isRegularFile(path)&&Files.size(path)<=4096);
            assertTrue(Set.of(PosixFilePermission.OWNER_READ,PosixFilePermission.OWNER_WRITE).containsAll(Files.getPosixFilePermissions(path)));
            var p=new ObjectMapper().readTree(Files.readAllBytes(path));var keys=new HashSet<String>();p.fieldNames().forEachRemaining(keys::add);
            assertEquals(Set.of("purpose","host","port","database","user","password"),keys);assertEquals("CARD_HTTP_DISPOSABLE_TEST",p.path("purpose").asText());
            database=new CardRuntimeTestDatabase(p.path("host").asText(),p.path("port").asInt(),p.path("database").asText(),p.path("user").asText(),p.path("password").asText(),null,cacheName,cacheUser,cachePassword);
            assertEquals("127.0.0.1",database.host());assertTrue(database.port()>1024&&database.port()<65536);
            assertTrue(database.name().matches("cards_http_native_[a-z0-9_]{1,32}"));assertTrue(database.user().matches("[a-z][a-z0-9_]{1,62}"));assertTrue(database.password().length()>=32);
        }
        try(var c=DriverManager.getConnection(database.jdbcUrl(),database.user(),database.password());var s=c.createStatement();var r=s.executeQuery(
                "SELECT current_setting('server_version_num')::integer/10000,(SELECT count(*) FROM pg_tables WHERE schemaname='public')")){
            assertTrue(r.next());assertEquals(17,r.getInt(1));assertEquals(0,r.getInt(2));
        }
        try(var c=DriverManager.getConnection(database.jdbcUrl(),database.user(),database.password());var st=c.createStatement()){
            st.execute("CREATE ROLE "+cacheUser+" LOGIN PASSWORD '"+cachePassword+"' NOSUPERUSER NOBYPASSRLS NOINHERIT NOCREATEDB NOCREATEROLE;REVOKE CONNECT,CREATE,TEMP ON DATABASE "+database.name()+" FROM PUBLIC");
            st.execute("CREATE DATABASE "+cacheName);
        }
        try(var c=DriverManager.getConnection(database.cacheJdbcUrl(),database.user(),database.password());var st=c.createStatement()){
            st.execute("REVOKE CONNECT,CREATE,TEMP ON DATABASE "+cacheName+" FROM PUBLIC;GRANT CONNECT ON DATABASE "+cacheName+" TO "+cacheUser+";REVOKE CREATE ON SCHEMA public FROM PUBLIC;CREATE SCHEMA owned_cache;CREATE TABLE owned_cache.cache_entries(cache_name VARCHAR(255) NOT NULL,cache_key VARCHAR(1024) NOT NULL,value BYTEA NOT NULL,expires_at TIMESTAMPTZ,created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),PRIMARY KEY(cache_name,cache_key));CREATE INDEX cache_entries_expiry ON owned_cache.cache_entries(expires_at) WHERE expires_at IS NOT NULL;GRANT USAGE ON SCHEMA owned_cache TO "+cacheUser+";GRANT SELECT,INSERT,UPDATE,DELETE ON owned_cache.cache_entries TO "+cacheUser);
        }
        var denied=assertThrows(java.sql.SQLException.class,()->DriverManager.getConnection(database.jdbcUrl(),database.cacheUser(),database.cachePassword()));
        assertEquals("42501",denied.getSQLState());
        return database;
    }
    String jdbcUrl(){return "jdbc:postgresql://"+host+":"+port+"/"+name;}
    String cacheJdbcUrl(){return "jdbc:postgresql://"+host+":"+port+"/"+cacheName;}
    void admitRuntime(String login,String secret)throws Exception {
        assertTrue(login.matches("[a-z][a-z0-9_]{1,62}"));
        try(var c=DriverManager.getConnection(jdbcUrl(),user,password);var st=c.createStatement()){
            st.execute("GRANT CONNECT ON DATABASE "+name+" TO "+login);
        }
        var denied=assertThrows(java.sql.SQLException.class,()->DriverManager.getConnection(cacheJdbcUrl(),login,secret));assertEquals("42501",denied.getSQLState());
    }
    void configureCache(ProcessBuilder process){
        process.environment().put("CARD_HTTP_CACHE_PASSWORD",cachePassword);
        process.command().addAll(List.of("--firefly.cache.default-cache-type=POSTGRES","--firefly.cache.postgres.enabled=true",
                "--firefly.cache.postgres.host="+host,"--firefly.cache.postgres.port="+port,"--firefly.cache.postgres.database="+cacheName,
                "--firefly.cache.postgres.username="+cacheUser,"--firefly.cache.postgres.password=${CARD_HTTP_CACHE_PASSWORD}",
                "--firefly.cache.postgres.ssl-mode=disable","--firefly.cache.postgres.schema=owned_cache","--firefly.cache.postgres.cache-table=cache_entries",
                "--firefly.cache.postgres.auto-create-schema=false","--firefly.cache.postgres.min-pool-size=1","--firefly.cache.postgres.max-pool-size=2",
                "--firefly.cache.postgres.connect-timeout=5s","--firefly.cache.postgres.acquire-timeout=5s","--firefly.cache.postgres.initialization-timeout=15s","--firefly.cache.postgres.shutdown-timeout=5s"));
    }
    @Override public String toString(){return "CardRuntimeTestDatabase[private owned profile]";}
    void stopOwnedContainer(){if(owned!=null)owned.stop();}
}
