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
class JpaCartFailureIntegrationTests extends CartTestSupport {
    @DynamicPropertySource static void failureDatabase(DynamicPropertyRegistry properties){
        String database=System.getenv("PUBBURI_JPA_FAILURE_DB_NAME"),token=System.getenv("PUBBURI_JPA_FAILURE_RUN_TOKEN"),url=System.getenv("PUBBURI_PG_URL");
        assertNotNull(database);assertNotNull(token);assertNotNull(url);assertNotEquals(database,System.getenv("PUBBURI_PG_DB_NAME"));
        properties.add("PUBBURI_PG_DB_NAME",()->database);properties.add("PUBBURI_PG_RUN_TOKEN",()->token);properties.add("PUBBURI_PG_URL",()->url.substring(0,url.lastIndexOf('/')+1)+database);
    }
    AutoCloseable failReceipt(JdbcTemplate sql,String user,UUID operation){
        assertEquals(required("PUBBURI_JPA_FAILURE_DB_NAME"),required("PUBBURI_PG_DB_NAME"));assertTrue(user.matches("ci_jpa_account_[a-f0-9]{32}"));
        sql.execute("CREATE FUNCTION pubburi.ci_jpa_cart_receipt_failure() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.user_id='"+user+"' AND NEW.operation_id='"+operation+"'::uuid THEN RAISE EXCEPTION 'authored cart receipt failure'; END IF; RETURN NEW; END $$");
        try{sql.execute("CREATE TRIGGER ci_jpa_cart_receipt_failure AFTER INSERT ON pubburi.t_user_cart_operation FOR EACH ROW EXECUTE FUNCTION pubburi.ci_jpa_cart_receipt_failure()");}
        catch(Throwable error){sql.execute("DROP FUNCTION pubburi.ci_jpa_cart_receipt_failure()");throw error;}
        return ()->{try{sql.execute("DROP TRIGGER ci_jpa_cart_receipt_failure ON pubburi.t_user_cart_operation");}finally{sql.execute("DROP FUNCTION pubburi.ci_jpa_cart_receipt_failure()");}};
    }
    @Test void failedCheckoutReceiptRollsBackOrderCartCountersAndRevisionBeforeSameIntentRetry()throws Exception{failed(true);}
    @Test void failedMergeReceiptNeverPartlyCombinesQuantitiesAndCanRetryTheSameOperation()throws Exception{failed(false);}
    @Test void failedRetirementRollsBackHeaderRevisionAndReceiptBeforeExplicitRetry()throws Exception{
        var sql=control();var admin=loggedIn("ADMIN");String user=join();int product=create(admin,ownName());UUID operation=UUID.randomUUID();
        try{
            var owner=login(user,password);var intent=resolution(false,List.of(item(product,2)));var before=cartState(sql,user);var account=accountState(sql,user);
            try(var fault=failReceipt(sql,user,operation)){assertEquals(500,cart(owner,user,"POST","/operations/"+operation+"/resolve",intent).status());assertEquals(before,cartState(sql,user));assertEquals(account,accountState(sql,user));assertEquals(404,cart(owner,user,"GET","/operations/"+operation,null).status());}
            var retry=cart(owner,user,"POST","/operations/"+operation+"/resolve",intent);assertEquals(200,retry.status());assertEquals("RETIRED",retry.body().at("/data/status").textValue());assertFalse(retry.body().at("/data/replayed").booleanValue());assertEquals(1,retry.body().at("/data/cart/version").longValue());assertTrue(retry.body().at("/data/cart/items").isEmpty());
            assertEquals("CART_OPERATION_RETIRED",cart(owner,user,"POST","/merge",merge(operation,1,List.of(item(product,2)))).body().at("/error/code").textValue());
        }finally{clean(sql,user,product,List.of());}
    }
    @Test void operationReadDatabaseFailureIsNeverConvertedToMissingReceipt()throws Exception{
        var sql=control();String user=join();String runtimeRole=required("PUBBURI_PG_USER");assertTrue(runtimeRole.matches("[a-z][a-z0-9_]{0,50}"));
        try{var owner=login(user,password);var before=cartState(sql,user);sql.execute("REVOKE SELECT ON pubburi.t_user_cart_operation FROM "+runtimeRole);
            try{assertEquals(500,cart(owner,user,"GET","/operations/"+UUID.randomUUID(),null).status());}
            finally{sql.execute("GRANT SELECT ON pubburi.t_user_cart_operation TO "+runtimeRole);}
            assertEquals(before,cartState(sql,user));assertEquals(404,cart(owner,user,"GET","/operations/"+UUID.randomUUID(),null).status());
        }finally{clean(sql,user,null,List.of());}
    }
    void failed(boolean checkout)throws Exception{
        var sql=control();var admin=loggedIn("ADMIN");String user=join();int product=create(admin,ownName());
        try{
            var owner=login(user,password);var current=put(owner,user,product,3,0);UUID operation=UUID.randomUUID();var payload=checkout?checkout(operation,version(current),List.of(item(product,1))):merge(operation,version(current),List.of(item(product,2)));
            var cartBefore=cartState(sql,user);var accountBefore=accountState(sql,user);var productBefore=state(sql,product);var sourceBefore=sourceIdentity(sql);
            try(var fault=failReceipt(sql,user,operation)){
                assertEquals(500,cart(owner,user,"POST",checkout?"/checkout":"/merge",payload).status());assertEquals(cartBefore,cartState(sql,user));assertEquals(accountBefore,accountState(sql,user));assertEquals(productBefore,state(sql,product));assertEquals(sourceBefore,sourceIdentity(sql));assertEquals(current,get(owner,user));
            }
            long consumed=sql.queryForObject("SELECT last_value FROM pubburi.t_order_o_id_seq",Long.class);
            var retry=cart(owner,user,"POST",checkout?"/checkout":"/merge",payload);assertEquals(200,retry.status());assertFalse(retry.body().at("/data/replayed").booleanValue());assertEquals(version(current)+1,retry.body().at("/data/cart/version").longValue());
            assertEquals(checkout?2:5,line(retry.body().at("/data/cart"),product).path("quantity").intValue());if(checkout)assertTrue(retry.body().at("/data/orderId").longValue()>consumed);
            var replay=cart(owner,user,"POST",checkout?"/checkout":"/merge",payload);assertEquals(200,replay.status());assertTrue(replay.body().at("/data/replayed").booleanValue());assertEquals(retry.body().at("/data/cart"),replay.body().at("/data/cart"));assertEquals(retry.body().at("/data/orderId"),replay.body().at("/data/orderId"));
        }finally{clean(sql,user,product,List.of());}
    }
}
