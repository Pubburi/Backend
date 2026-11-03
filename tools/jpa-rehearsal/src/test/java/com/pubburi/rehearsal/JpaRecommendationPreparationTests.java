package com.pubburi.rehearsal;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;

/** Explicit disposable CI scratch authoring only; never packaged or run during ordinary integration. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties="server.port=0")
@EnabledIfEnvironmentVariable(named="PUBBURI_JPA_PREPARE_RECOMMENDATION",matches="1")
class JpaRecommendationPreparationTests extends JpaAccountTestSupport {
    @Test void emptyOnlyTheExplicitRecommendationCopyUsingTheOriginalAdminApi() throws Exception {
        assertEquals("1",required("PUBBURI_JPA_CI"));String database=required("PUBBURI_PG_DB_NAME");
        assertEquals(required("PUBBURI_PG_JPA_RECOMMENDATION_DB_NAME"),database);assertEquals(required("PUBBURI_JPA_RECOMMENDATION_DB_NAME"),database);
        assertNotEquals(required("PUBBURI_PG_JPA_DB_NAME"),database);assertNotEquals(required("PUBBURI_PG_JPA_FAILURE_DB_NAME"),database);
        var sql=control();var identity=sourceIdentity(sql);var admin=loggedIn("ADMIN");
        var ids=sql.queryForList("SELECT id FROM pubburi.t_product ORDER BY id",Integer.class);assertFalse(ids.isEmpty(),"never silently reuse an already emptied scratch");
        for(int id:ids)assertEquals(200,request(admin,"DELETE","/api/admin/products/"+id,null).status());
        assertEquals(0,sql.queryForObject("SELECT count(*) FROM pubburi.t_product",Integer.class));assertEquals(identity,sourceIdentity(sql));
    }
}
