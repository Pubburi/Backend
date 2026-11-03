package com.pubburi.rehearsal;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties="server.port=0")
@EnabledIfEnvironmentVariable(named="PUBBURI_JPA_INTEGRATION",matches="1")
class JpaPreferenceFailureIntegrationTests extends JpaAccountTestSupport {
    static final String PATH="/api/users/me/preferences";
    @DynamicPropertySource static void failureDatabase(DynamicPropertyRegistry properties) {
        String database=System.getenv("PUBBURI_JPA_FAILURE_DB_NAME"),token=System.getenv("PUBBURI_JPA_FAILURE_RUN_TOKEN"),url=System.getenv("PUBBURI_PG_URL");
        assertNotNull(database); assertNotNull(token); assertNotNull(url); assertNotEquals(database,System.getenv("PUBBURI_PG_DB_NAME"));
        properties.add("PUBBURI_PG_DB_NAME",() -> database); properties.add("PUBBURI_PG_RUN_TOKEN",() -> token);
        properties.add("PUBBURI_PG_URL",() -> url.substring(0,url.lastIndexOf('/')+1)+database);
    }
    AutoCloseable trigger(JdbcTemplate sql,String table,String operation,String user) {
        assertEquals(required("PUBBURI_JPA_FAILURE_DB_NAME"),required("PUBBURI_PG_DB_NAME")); assertTrue(user.matches("ci_jpa_account_[a-f0-9]{32}"));
        assertTrue(Set.of("t_user_preference","t_user_preference_value").contains(table)); assertTrue(Set.of("INSERT","UPDATE","DELETE").contains(operation));
        String row=operation.equals("DELETE")?"OLD":"NEW";
        String partial=table.equals("t_user_preference_value")&&operation.equals("INSERT")?" AND NEW.kind='TASTE' AND NEW.value_text='explode'":"";
        sql.execute("CREATE FUNCTION pubburi.ci_jpa_preference_failure() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF "+row+".user_id='"+user+"'"+partial+" THEN RAISE EXCEPTION 'authored preference failure'; END IF; RETURN "+row+"; END $$");
        try { sql.execute("CREATE TRIGGER ci_jpa_preference_failure AFTER "+operation+" ON pubburi."+table+" FOR EACH ROW EXECUTE FUNCTION pubburi.ci_jpa_preference_failure()"); }
        catch(Throwable error) { sql.execute("DROP FUNCTION pubburi.ci_jpa_preference_failure()"); throw error; }
        return () -> { try { sql.execute("DROP TRIGGER ci_jpa_preference_failure ON pubburi."+table); } finally { sql.execute("DROP FUNCTION pubburi.ci_jpa_preference_failure()"); } };
    }
    @Test void headerInsertFailureLeavesAnActiveUnansweredAccount() throws Exception { failed("t_user_preference","INSERT"); }
    @Test void headerUpdateFailurePreservesPriorWholePreferences() throws Exception { failed("t_user_preference","UPDATE"); }
    @Test void valueDeleteFailureRollsBackTheAlreadyUpdatedHeader() throws Exception { failed("t_user_preference_value","DELETE"); }
    @Test void lateValueInsertFailureRollsBackHeaderDeletionAndEarlierInsertedValue() throws Exception { failed("t_user_preference_value","INSERT"); }
    void failed(String table,String operation) throws Exception {
        var sql=control(); var admin=loggedIn("ADMIN"); String user=join(); int product=create(admin,ownName());
        try {
            var owner=login(user,password);
            if(!table.equals("t_user_preference")||!operation.equals("INSERT")) assertEquals(200,request(owner,"PUT",PATH,Map.of("surveyStatus","COMPLETED","softPreferences",Map.of("categories",List.of("맥주"),"tasteTags",List.of("old"),"aromaTags",List.of("oldaroma")),"hardConstraints",Map.of("minPriceKrw",1))).status());
            var before=accountState(sql,user); var producer=state(sql,product); var identity=sourceIdentity(sql); var read=request(owner,"GET",PATH,null).body();
            var replacement=Map.of("surveyStatus","COMPLETED","softPreferences",Map.of("tasteTags",List.of("allowed","explode")),"hardConstraints",Map.of("minPriceKrw",2));
            try(var fault=trigger(sql,table,operation,user)) {
                assertEquals(500,request(owner,"PUT",PATH,replacement).status()); assertEquals(before,accountState(sql,user)); assertEquals(producer,state(sql,product)); assertEquals(identity,sourceIdentity(sql));
                assertEquals(read,request(owner,"GET",PATH,null).body());
            }
            assertEquals(200,request(owner,"PUT",PATH,replacement).status()); assertEquals(2,sql.queryForObject("SELECT count(*) FROM pubburi.t_user_preference_value WHERE user_id=?",Integer.class,user));
        } finally { clean(sql,user,product,List.of()); }
    }
}
