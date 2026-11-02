package com.pubburi.rehearsal;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import com.pubburi.pub.model.dao.*;
import com.pubburi.pub.model.dto.*;
import com.pubburi.pub.model.service.*;
import jakarta.persistence.EntityManagerFactory;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties="server.port=0")
@EnabledIfEnvironmentVariable(named="PUBBURI_JPA_INTEGRATION",matches="1")
class JpaHttpIntegrationTests {
    @LocalServerPort int port;
    @Autowired UserService users;@Autowired UserDao userDao;@Autowired PasswordHasher hasher;
    @Autowired JdbcTemplate sql;@Autowired DataSource dataSource;@Autowired PlatformTransactionManager transactions;
    @Autowired Environment environment;@Autowired EntityManagerFactory entities;
    final ObjectMapper json=new ObjectMapper();
    HttpClient client(){return HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).connectTimeout(Duration.ofSeconds(3)).build();}
    record Reply(int status,JsonNode body){ }
    Reply request(HttpClient c,String method,String path,Object body)throws Exception {
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(8)).header("Accept","application/json");
        builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body),StandardCharsets.UTF_8));if(body!=null)builder.header("Content-Type","application/json");
        var response=c.send(builder.build(),HttpResponse.BodyHandlers.ofString());return new Reply(response.statusCode(),json.readTree(response.body()));
    }
    void login(HttpClient client,String id,String pass)throws Exception{Reply r=request(client,"POST","/api/auth/login",Map.of("id",id,"pass",pass));assertEquals(200,r.status());assertFalse(r.body().toString().contains("authEpoch"));assertFalse(r.body().toString().contains("password_hash"));}
    String required(String key){String s=System.getenv(key);assertNotNull(s);return s;}
    JdbcTemplate control() throws Exception {
        var values=new HashMap<String,String>();for(String k:List.of("PUBBURI_JPA_REHEARSAL","PUBBURI_PG_URL","PUBBURI_PG_DB_NAME","PUBBURI_PG_USER","PUBBURI_PG_PASSWORD","PUBBURI_PG_RUN_TOKEN","PUBBURI_PG_CA","PUBBURI_JPA_SOURCE_ID"))values.put(k,required(k));
        var settings=RehearsalSettings.from(values);var ds=new org.springframework.jdbc.datasource.DriverManagerDataSource();ds.setUrl(settings.url());ds.setConnectionProperties(settings.properties());
        ds.setUsername(required("PUBBURI_JPA_CONTROL_USER"));ds.setPassword(required("PUBBURI_JPA_CONTROL_PASSWORD"));var control=new JdbcTemplate(ds);
        assertEquals(1,control.queryForObject("SELECT count(*) FROM public.pubburi_pg_migration_guard WHERE token=?",Integer.class,settings.token()));return control;
    }
    Map<String,Object> market(String name){return Map.of("name",name,"latitude",37.123456789,"longitude",127.987654321,"image","authored.png");}
    @Test void realHttpSessionPermissionsAndMarketCrudUseJpaAndPreserveDtoContract()throws Exception {
        for(String ddl:List.of("CREATE TABLE pubburi.ci_jpa_forbidden(id INT)","ALTER TABLE pubburi.t_market ADD COLUMN ci_jpa_forbidden INT","DROP TABLE pubburi.t_market")) {
            var rejected=assertThrows(org.springframework.dao.DataAccessException.class,()->sql.execute(ddl));assertEquals("42501",((SQLException)rejected.getMostSpecificCause()).getSQLState());
        }
        assertFalse(environment.getProperty("spring.jpa.open-in-view",Boolean.class,true));assertEquals("validate",environment.getProperty("spring.jpa.hibernate.ddl-auto"));
        String adminId=required("PUBBURI_JPA_ADMIN_ID"),adminPass=required("PUBBURI_JPA_ADMIN_PASSWORD");assertTrue(adminId.startsWith("ci_jpa_"));
        var anonymous=client();assertEquals(401,request(anonymous,"POST","/api/admin/markets",market("Authored unauthorized")).status());
        var ordinary=client();login(ordinary,required("PUBBURI_JPA_USER_ID"),required("PUBBURI_JPA_USER_PASSWORD"));assertEquals(403,request(ordinary,"POST","/api/admin/markets",market("Authored forbidden")).status());
        var admin=client();login(admin,adminId,adminPass);Reply page=request(admin,"GET","/api/markets?page=1&size=2",null);assertEquals(200,page.status());assertTrue(page.body().path("data").path("items").size()<=2);
        assertEquals(200,request(admin,"GET","/api/products",null).status());assertEquals(404,request(admin,"GET","/api/users/anything/profile",null).status());
        var created=request(admin,"POST","/api/admin/markets",market("Authored JPA market"));assertEquals(201,created.status());int id=created.body().path("data").path("id").intValue();assertTrue(id>0);
        var keys=new HashSet<String>();created.body().path("data").fieldNames().forEachRemaining(keys::add);assertEquals(Set.of("id","name","latitude","longitude","image"),keys);
        assertEquals(37.123456789,created.body().path("data").path("latitude").doubleValue());
        assertEquals(200,request(admin,"PUT","/api/admin/markets/"+id,market("Authored edited")).status());assertEquals("Authored edited",request(admin,"GET","/api/markets/"+id,null).body().path("data").path("name").textValue());
        var invalid=new HashMap<>(market("Authored invalid"));invalid.put("latitude",91);assertEquals(400,request(admin,"POST","/api/admin/markets",invalid).status());
        long before=sql.queryForObject("SELECT count(*) FROM pubburi.t_market",Long.class);
        JdbcTemplate fixture=control();
        fixture.execute("CREATE FUNCTION pubburi.ci_jpa_fail_market() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.name LIKE 'ci_jpa_fail_%' THEN RAISE EXCEPTION 'authored JPA market failure'; END IF; RETURN NEW; END $$");
        fixture.execute("CREATE TRIGGER ci_jpa_fail_market AFTER INSERT ON pubburi.t_market FOR EACH ROW EXECUTE FUNCTION pubburi.ci_jpa_fail_market()");
        try {assertEquals(500,request(admin,"POST","/api/admin/markets",market("ci_jpa_fail_"+UUID.randomUUID())).status());assertEquals(before,sql.queryForObject("SELECT count(*) FROM pubburi.t_market",Long.class));}
        finally {fixture.execute("DROP TRIGGER ci_jpa_fail_market ON pubburi.t_market");fixture.execute("DROP FUNCTION pubburi.ci_jpa_fail_market()");}
        User safe=users.getUserById(adminId);assertNull(safe.getPass());assertNotNull(userDao.selectById(adminId).getPass(),"DTO.withoutPass must not mutate managed persistence");
        User original=userDao.selectById(adminId);
        try {users.modifyUser(adminId,new UserUpdate("Authored changed admin",null,7,"USER"));assertEquals("USER",request(admin,"GET","/api/auth/me",null).body().path("data").path("role").textValue());assertEquals(403,request(admin,"DELETE","/api/admin/markets/"+id,null).status());}
        finally {users.modifyUser(adminId,new UserUpdate(original.getName(),null,original.getStamps(),original.getRole()));}
        assertEquals(200,request(admin,"DELETE","/api/admin/markets/"+id,null).status());assertEquals(404,request(admin,"GET","/api/markets/"+id,null).status());
        var docs=request(admin,"GET","/v3/api-docs",null);assertEquals(200,docs.status());var paths=new HashSet<String>();docs.body().path("paths").fieldNames().forEachRemaining(paths::add);
        assertEquals(Set.of("/api/auth/login","/api/auth/logout","/api/auth/me","/api/markets","/api/markets/{id}","/api/admin/markets","/api/admin/markets/{id}",
            "/api/products","/api/products/popular","/api/products/{id}","/api/products/{id}/features"),paths);
        assertEquals(200,request(admin,"POST","/api/auth/logout",null).status());assertEquals(401,request(admin,"GET","/api/auth/me",null).status());
    }
    @Test void passwordCasCurrentProjectionAndFullUserLockRespectCredentialIncarnation()throws Exception {
        String id="ci_jpa_cas_"+UUID.randomUUID().toString().replace("-","");String pass="authored-old-password",fresh="authored-new-password";
        User user=new User(id,"Authored CAS",pass,0,"USER");assertEquals(1,users.addUser(user));
        try {
            User before=userDao.selectById(id);User duplicate=new User(id.toUpperCase(Locale.ROOT),"Do not replace","authored-not-a-login-hash",99,"ADMIN");duplicate.setAuthEpoch(UUID.randomUUID().toString());
            assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->userDao.insert(duplicate));
            User unchanged=userDao.selectById(id);assertEquals("USER",unchanged.getRole());assertTrue(before.getPass().equals(unchanged.getPass()),"duplicate insert must preserve credentials");assertTrue(before.getAuthEpoch().equals(unchanged.getAuthEpoch()),"duplicate insert must preserve incarnation");
            String legacy=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(pass.getBytes(StandardCharsets.UTF_8)));
            sql.update("UPDATE pubburi.t_user SET password_hash=? WHERE id=?",legacy,id);
            var old=client();login(old,id,pass);assertTrue(userDao.selectById(id).getPass().startsWith("$2"));
            User captured=userDao.selectById(id);var dormant=client();login(dormant,id,pass);
            users.modifyUser(id,new UserUpdate(null,fresh,null,null));assertEquals(0,userDao.upgradePassword(id,captured.getPass(),captured.getAuthEpoch(),hasher.hash(pass)));
            assertEquals(401,request(old,"GET","/api/auth/me",null).status());assertEquals(401,request(client(),"POST","/api/auth/login",Map.of("id",id,"pass",pass)).status());login(client(),id,fresh);
            ExecutorService executor=Executors.newSingleThreadExecutor();
            try {new TransactionTemplate(transactions).execute(status->{
                assertNotNull(userDao.selectByIdForUpdate(id));Future<String> result=executor.submit(()->{try(Connection c=dataSource.getConnection();Statement s=c.createStatement()) {
                    c.setAutoCommit(false);s.execute("SET LOCAL lock_timeout='250ms'");try(var r=s.executeQuery("SELECT id FROM pubburi.t_user WHERE id='"+id+"' FOR KEY SHARE")){r.next();return "unexpected_lock_acquired";}
                    catch(SQLException e){c.rollback();return e.getSQLState();}
                }});
                try {assertEquals("55P03",result.get(3,TimeUnit.SECONDS));}catch(Exception e){throw new IllegalStateException("owned lock experiment failed");}return null;
            });}finally {executor.shutdownNow();assertTrue(executor.awaitTermination(3,TimeUnit.SECONDS));}
            assertEquals(1,users.removeUser(id));assertEquals(1,users.addUser(new User(id,"Authored recreated",fresh,0,"USER")));
            assertEquals(401,request(dormant,"GET","/api/auth/me",null).status());login(client(),id,fresh);assertEquals(0,userDao.upgradePassword(id,captured.getPass(),captured.getAuthEpoch(),hasher.hash(pass)));
        } finally {users.removeUser(id);}
    }
}
