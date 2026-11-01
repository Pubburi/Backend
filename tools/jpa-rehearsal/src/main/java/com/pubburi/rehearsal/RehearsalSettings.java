package com.pubburi.rehearsal;

import java.net.URI;
import java.nio.file.*;
import java.util.*;

record RehearsalSettings(String url,String database,String user,String password,String token,Path ca,String sourceId) {
    static RehearsalSettings from(Map<String,String> values) {
        try {
            if(!"1".equals(values.get("PUBBURI_JPA_REHEARSAL")) || !"127.0.0.1".equals(values.getOrDefault("server.address","127.0.0.1")))throw new IllegalArgumentException();
            String url=required(values,"PUBBURI_PG_URL");if(!url.startsWith("jdbc:postgresql://"))throw new IllegalArgumentException();
            URI uri=URI.create(url.substring(5));String db=required(values,"PUBBURI_PG_DB_NAME");
            if(!Set.of("127.0.0.1","::1","[::1]").contains(uri.getHost()) || uri.getPort()<1 || uri.getPort()>65535 || uri.getRawQuery()!=null || uri.getUserInfo()!=null || uri.getRawFragment()!=null || !db.matches("[a-z][a-z0-9_]*_ci") || !uri.getRawPath().equals("/"+db))throw new IllegalArgumentException();
            String token=required(values,"PUBBURI_PG_RUN_TOKEN"),source=required(values,"PUBBURI_JPA_SOURCE_ID");
            if(!token.matches("[A-Za-z0-9_-]{12,100}") || !UUID.fromString(source).toString().equals(source))throw new IllegalArgumentException();
            Path ca=Path.of(required(values,"PUBBURI_PG_CA"));if(!Files.isRegularFile(ca) || !Files.isReadable(ca))throw new IllegalArgumentException();
            return new RehearsalSettings(url,db,required(values,"PUBBURI_PG_USER"),required(values,"PUBBURI_PG_PASSWORD"),token,ca,source);
        } catch(RuntimeException error){throw new IllegalArgumentException("jpa_rehearsal_explicit_fixture_required");}
    }
    static String required(Map<String,String> env,String key){String value=env.get(key);if(value==null || value.isBlank())throw new IllegalArgumentException();return value;}
    Properties properties(){
        var p=new Properties();p.setProperty("sslmode","verify-full");p.setProperty("sslrootcert",ca.toAbsolutePath().toString());p.setProperty("gssEncMode","disable");
        p.setProperty("requireAuth","scram-sha-256");p.setProperty("connectTimeout","3");p.setProperty("socketTimeout","5");
        p.setProperty("options","-c timezone=UTC -c statement_timeout=5000 -c lock_timeout=2000");return p;
    }
    @Override public String toString(){return "RehearsalSettings[redacted]";}
}
