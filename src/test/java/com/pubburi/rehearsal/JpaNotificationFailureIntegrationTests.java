package com.pubburi.rehearsal;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties="server.port=0")
@EnabledIfEnvironmentVariable(named="PUBBURI_JPA_INTEGRATION",matches="1")
class JpaNotificationFailureIntegrationTests extends NotificationTestSupport {
    @DynamicPropertySource static void failureDatabase(DynamicPropertyRegistry properties){
        String database=System.getenv("PUBBURI_JPA_FAILURE_DB_NAME"),token=System.getenv("PUBBURI_JPA_FAILURE_RUN_TOKEN"),url=System.getenv("PUBBURI_PG_URL");assertNotNull(database);assertNotNull(token);assertNotNull(url);assertNotEquals(database,System.getenv("PUBBURI_PG_DB_NAME"));
        properties.add("PUBBURI_PG_DB_NAME",()->database);properties.add("PUBBURI_PG_RUN_TOKEN",()->token);properties.add("PUBBURI_PG_URL",()->url.substring(0,url.lastIndexOf('/')+1)+database);
    }
    AutoCloseable failInsert(JdbcTemplate sql,String user){
        assertEquals(required("PUBBURI_JPA_FAILURE_DB_NAME"),required("PUBBURI_PG_DB_NAME"));assertTrue(user.matches("ci_jpa_account_[a-f0-9]{32}"));
        sql.execute("CREATE FUNCTION pubburi.ci_jpa_notification_failure() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.user_id='"+user+"' THEN RAISE EXCEPTION 'authored notification failure'; END IF; RETURN NEW; END $$");
        try{sql.execute("CREATE TRIGGER ci_jpa_notification_failure AFTER INSERT ON pubburi.t_user_notification FOR EACH ROW EXECUTE FUNCTION pubburi.ci_jpa_notification_failure()");}
        catch(Throwable error){sql.execute("DROP FUNCTION pubburi.ci_jpa_notification_failure()");throw error;}
        return ()->{try{sql.execute("DROP TRIGGER ci_jpa_notification_failure ON pubburi.t_user_notification");}finally{sql.execute("DROP FUNCTION pubburi.ci_jpa_notification_failure()");}};
    }
    AutoCloseable failRead(JdbcTemplate sql,String user,UUID id){
        assertEquals(required("PUBBURI_JPA_FAILURE_DB_NAME"),required("PUBBURI_PG_DB_NAME"));assertTrue(user.matches("ci_jpa_account_[a-f0-9]{32}"));
        sql.execute("CREATE FUNCTION pubburi.ci_jpa_notification_read_failure() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.user_id='"+user+"' AND NEW.id='"+id+"'::uuid THEN RAISE EXCEPTION 'authored notification read failure'; END IF; RETURN NEW; END $$");
        try{sql.execute("CREATE TRIGGER ci_jpa_notification_read_failure AFTER UPDATE ON pubburi.t_user_notification FOR EACH ROW EXECUTE FUNCTION pubburi.ci_jpa_notification_read_failure()");}
        catch(Throwable error){sql.execute("DROP FUNCTION pubburi.ci_jpa_notification_read_failure()");throw error;}
        return ()->{try{sql.execute("DROP TRIGGER ci_jpa_notification_read_failure ON pubburi.t_user_notification");}finally{sql.execute("DROP FUNCTION pubburi.ci_jpa_notification_read_failure()");}};
    }
    @Test void notificationFailureRollsBackBothBusinessEventsBeforeAValidRetry()throws Exception{
        var sql=control();var admin=loggedIn("ADMIN");String user=join();int product=create(admin,ownName());
        try{
            var owner=login(user,password);int order=order(owner,product),review=review(owner,product);var before=accountState(sql,user);var reviews=sql.queryForList("SELECT * FROM pubburi.t_comment WHERE id=?",review);
            try(var fault=failInsert(sql,user)){
                assertEquals(500,request(admin,"PATCH","/api/admin/orders/"+order+"/complete",null).status());assertEquals(before,accountState(sql,user));assertTrue(stored(sql,user).isEmpty());
                assertEquals(500,request(admin,"DELETE","/api/comments/"+review,null).status());assertEquals(reviews,sql.queryForList("SELECT * FROM pubburi.t_comment WHERE id=?",review));assertTrue(stored(sql,user).isEmpty());
            }
            assertEquals(200,request(admin,"PATCH","/api/admin/orders/"+order+"/complete",null).status());assertEquals(200,request(admin,"DELETE","/api/comments/"+review,null).status());assertEquals(2,page(owner,user).path("unreadCount").intValue());
            var ids=new ArrayList<String>();for(var row:page(owner,user).path("items"))ids.add(row.path("id").textValue());var unread=stored(sql,user);
            try(var fault=failRead(sql,user,UUID.fromString(ids.get(1)))){
                assertEquals(500,notification(owner,user,"POST","/read",Map.of("ids",ids)).status());assertEquals(unread,stored(sql,user));
            }
            assertEquals(0,notification(owner,user,"POST","/read",Map.of("ids",ids)).body().at("/data/unreadCount").intValue());
            String role=required("PUBBURI_PG_USER");assertTrue(role.matches("[a-z][a-z0-9_]{0,50}"));var state=stored(sql,user);sql.execute("REVOKE SELECT ON pubburi.t_user_notification FROM "+role);
            try{assertEquals(500,notification(owner,user,"GET","",null).status());}
            finally{sql.execute("GRANT SELECT ON pubburi.t_user_notification TO "+role);}
            assertEquals(state,stored(sql,user));
        }finally{clean(sql,user,product,List.of());}
    }
}
