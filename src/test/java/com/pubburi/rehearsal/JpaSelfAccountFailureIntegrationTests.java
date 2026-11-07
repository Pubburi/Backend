package com.pubburi.rehearsal;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties="server.port=0")
@EnabledIfEnvironmentVariable(named="PUBBURI_JPA_INTEGRATION", matches="1")
class JpaSelfAccountFailureIntegrationTests extends JpaAccountTestSupport {
    @DynamicPropertySource static void failureDatabase(DynamicPropertyRegistry properties) {
        String database=System.getenv("PUBBURI_JPA_FAILURE_DB_NAME"),token=System.getenv("PUBBURI_JPA_FAILURE_RUN_TOKEN"),url=System.getenv("PUBBURI_PG_URL");
        assertNotNull(database); assertNotNull(token); assertNotNull(url); assertNotEquals(database,System.getenv("PUBBURI_PG_DB_NAME"));
        properties.add("PUBBURI_PG_DB_NAME",() -> database); properties.add("PUBBURI_PG_RUN_TOKEN",() -> token); properties.add("PUBBURI_PG_URL",() -> url.substring(0,url.lastIndexOf('/')+1)+database);
    }
    AutoCloseable trigger(JdbcTemplate sql,String id) {
        assertEquals(required("PUBBURI_JPA_FAILURE_DB_NAME"),required("PUBBURI_PG_DB_NAME")); assertTrue(id.matches("ci_jpa_account_[a-f0-9]{32}"));
        sql.execute("CREATE FUNCTION pubburi.ci_jpa_self_failure() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.id='"+id+"' THEN RAISE EXCEPTION 'authored self failure'; END IF; RETURN NEW; END $$");
        try { sql.execute("CREATE TRIGGER ci_jpa_self_failure AFTER UPDATE ON pubburi.t_user FOR EACH ROW EXECUTE FUNCTION pubburi.ci_jpa_self_failure()"); }
        catch(Throwable e) { sql.execute("DROP FUNCTION pubburi.ci_jpa_self_failure()"); throw e; }
        return () -> { try { sql.execute("DROP TRIGGER ci_jpa_self_failure ON pubburi.t_user"); } finally { sql.execute("DROP FUNCTION pubburi.ci_jpa_self_failure()"); } };
    }
    @Test void profileAfterUpdateFailureRollsBackNameAndKeepsBothSessionsAndAllPrivateState() throws Exception { failedWrite(false); }
    @Test void passwordAfterUpdateFailureRollsBackHashEpochAndDoesNotRefreshOrRevokeSessions() throws Exception { failedWrite(true); }
    void failedWrite(boolean passwordChange) throws Exception {
        var sql=control(); var admin=loggedIn("ADMIN"); String id=join(); int product=create(admin,ownName());
        try {
            var owner=login(id,password); var other=login(id,password); children(sql,id,product); var before=accountState(sql,id); var source=sourceIdentity(sql); var productBefore=state(sql,product);
            String path=passwordChange?"/api/users/me/password":"/api/users/me/profile";
            Map<String,String> body=passwordChange?Map.of("currentPassword",password,"newPassword","recovered pass"):Map.of("name","recovered name");
            try(var fault=trigger(sql,id)) {
                assertEquals(500,request(owner,"PUT",path,body).status()); assertEquals(before,accountState(sql,id)); assertEquals(source,sourceIdentity(sql)); assertEquals(productBefore,state(sql,product));
                assertEquals(200,request(owner,"GET","/api/auth/me",null).status()); assertEquals(200,request(other,"GET","/api/auth/me",null).status()); login(id,password);
                assertEquals(401,request(client(),"POST","/api/auth/login",Map.of("id",id,"pass","recovered pass")).status());
            }
            assertEquals(200,request(owner,"PUT",path,body).status());
            assertEquals(passwordChange?401:200,request(other,"GET","/api/auth/me",null).status());
        } finally { clean(sql,id,product,List.of()); }
    }
}
