package com.pubburi.migration;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

record SourceSettings(String url,String database,String user,String password,Path truststore,String trustPassword,
                      String token,String window) {
    static SourceSettings from(Map<String,String> env) {
        try {
            String url=required(env,"PUBBURI_SOURCE_URL");
            if(!url.startsWith("jdbc:mysql://")) throw new IllegalArgumentException();
            URI uri=URI.create(url.substring(5));String database=required(env,"PUBBURI_SOURCE_DB_NAME");
            if(!("127.0.0.1".equals(uri.getHost()) || "[::1]".equals(uri.getHost()) || "::1".equals(uri.getHost()))
                    || uri.getPort()<1 || uri.getPort()>65535 || uri.getUserInfo()!=null || uri.getRawQuery()!=null || uri.getRawFragment()!=null
                    || !database.matches("[a-z][a-z0-9_]*_ci") || !uri.getRawPath().equals('/'+database)
                    || !"1".equals(env.get("PUBBURI_SOURCE_WRITES_STOPPED")) || !"UTC".equals(env.get("PUBBURI_SOURCE_DATETIME_ZONE"))) throw new IllegalArgumentException();
            Path store=Path.of(required(env,"PUBBURI_SOURCE_TRUSTSTORE"));
            if(!Files.isRegularFile(store) || !Files.isReadable(store)) throw new IllegalArgumentException();
            String token=required(env,"PUBBURI_SOURCE_RUN_TOKEN"),window=required(env,"PUBBURI_SOURCE_WINDOW_UUID");
            if(!token.matches("[A-Za-z0-9_-]{12,100}") || !UUID.fromString(window).toString().equals(window)) throw new IllegalArgumentException();
            return new SourceSettings(url,database,required(env,"PUBBURI_SOURCE_USER"),required(env,"PUBBURI_SOURCE_PASSWORD"),store,
                    required(env,"PUBBURI_SOURCE_TRUSTSTORE_PASSWORD"),token,window);
        } catch(RuntimeException error) {throw new IllegalArgumentException("source_explicit_read_only_fixture_required");}
    }
    static String required(Map<String,String> env,String key) {
        String value=env.get(key);if(value==null || value.isBlank()) throw new IllegalArgumentException("source_setting_missing");return value;
    }
    Properties properties() {
        Properties p=new Properties();p.setProperty("user",user);p.setProperty("password",password);
        p.setProperty("sslMode","VERIFY_IDENTITY");p.setProperty("trustCertificateKeyStoreUrl",truststore.toAbsolutePath().toUri().toString());
        p.setProperty("trustCertificateKeyStoreType","PKCS12");p.setProperty("trustCertificateKeyStorePassword",trustPassword);
        p.setProperty("fallbackToSystemTrustStore","false");p.setProperty("allowPublicKeyRetrieval","false");
        p.setProperty("allowLoadLocalInfile","false");p.setProperty("allowUrlInLocalInfile","false");
        p.setProperty("connectTimeout","3000");p.setProperty("socketTimeout","5000");
        p.setProperty("connectionTimeZone","UTC");p.setProperty("forceConnectionTimeZoneToSession","true");
        p.setProperty("characterEncoding","UTF-8");p.setProperty("tinyInt1isBit","false");
        p.setProperty("readOnlyPropagatesToServer","true");p.setProperty("useLocalSessionState","false");
        return p;
    }
    Connection connect() throws SQLException {return DriverManager.getConnection(url,properties());}
    @Override public String toString(){return "SourceSettings[redacted]";}
}
