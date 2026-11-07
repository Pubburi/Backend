package com.pubburi.rehearsal;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;

/** Dedicated root-authored empty-catalog DB; never deletes copied catalog rows in the normal DB. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties="server.port=0")
@Import(JpaDefaultRecommendationIntegrationTests.Probes.class)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIfEnvironmentVariable(named="PUBBURI_JPA_INTEGRATION",matches="1")
class JpaDefaultRecommendationLifecycleIntegrationTests extends JpaAccountTestSupport {
    static final String PATH="/api/recommendations/default";
    static JpaDefaultRecommendationIntegrationTests.OwnedB0 upstream;
    @Autowired JpaDefaultRecommendationIntegrationTests.ObservedGateway observed;
    @DynamicPropertySource static void scratch(DynamicPropertyRegistry properties) throws IOException {
        String database=System.getenv("PUBBURI_JPA_RECOMMENDATION_DB_NAME"),token=System.getenv("PUBBURI_JPA_RECOMMENDATION_RUN_TOKEN"),url=System.getenv("PUBBURI_PG_URL");
        assertNotNull(database);assertNotNull(token);assertNotNull(url);assertNotEquals(database,System.getenv("PUBBURI_PG_DB_NAME"));assertNotEquals(database,System.getenv("PUBBURI_JPA_FAILURE_DB_NAME"));
        properties.add("PUBBURI_PG_DB_NAME",() -> database);properties.add("PUBBURI_PG_RUN_TOKEN",() -> token);properties.add("PUBBURI_PG_URL",() -> url.substring(0,url.lastIndexOf('/')+1)+database);
        upstream=new JpaDefaultRecommendationIntegrationTests.OwnedB0();properties.add("pubburi.recommendation.base-url",upstream::origin);
    }
    @BeforeEach void reset() { upstream.reset();observed.insideTransaction.set(null);assertEquals(required("PUBBURI_JPA_RECOMMENDATION_DB_NAME"),required("PUBBURI_PG_DB_NAME"));assertEquals(0,control().queryForObject("SELECT count(*) FROM pubburi.t_product",Integer.class)); }
    @AfterAll static void stopOwnedUpstream() { if(upstream!=null)upstream.close(); }
    void removeOwned(JdbcTemplate sql,List<Integer> ids) { ids.forEach(id -> sql.update("DELETE FROM pubburi.t_product WHERE id=? AND name LIKE 'ci_jpa_write_%'",id)); }
    void result(Reply reply,String source,List<Integer> ids) {
        assertEquals(200,reply.status());assertEquals(Set.of("items","source"),keys(reply.body().path("data")));assertEquals(source,reply.body().path("data").path("source").textValue());
        var actual=new ArrayList<Integer>();reply.body().path("data").path("items").forEach(p -> actual.add(p.path("id").intValue()));assertEquals(ids,actual);
    }
    @Test void emptyCatalogIsFallbackWithoutRemoteIoAndNewCurrentRowsUseBaseline() throws Exception {
        var sql=control();var admin=loggedIn("ADMIN");var own=new ArrayList<Integer>();
        try {
            result(request(client(),"GET",PATH,null),"FALLBACK",List.of());assertEquals(0,upstream.calls.get());assertNull(observed.insideTransaction.get());
            int id=create(admin,ownName());own.add(id);result(request(client(),"GET",PATH,null),"BASELINE",List.of(id));assertEquals(1,upstream.calls.get());assertEquals(Boolean.FALSE,observed.insideTransaction.get());
        } finally { removeOwned(sql,own); }
    }

    @Test void heldRealHttpSelectionRereadsChangedCurrentFieldsAfterAdminCommit() throws Exception {
        var sql=control();var admin=loggedIn("ADMIN");var own=new ArrayList<Integer>();var executor=Executors.newSingleThreadExecutor();var arrived=new CountDownLatch(1);var release=new CountDownLatch(1);
        try {
            own.add(create(admin,ownName()));own.add(create(admin,ownName()));
            upstream.handler.set((exchange,payload) -> { arrived.countDown();assertTrue(release.await(4,TimeUnit.SECONDS));upstream.reply(exchange,200,"application/json",upstream.valid(payload),false); });
            var reading=executor.submit(() -> request(client(),"GET",PATH+"?size=2",null));assertTrue(arrived.await(3,TimeUnit.SECONDS));
            assertEquals(200,request(admin,"PUT","/api/admin/products/"+own.get(0),product(ownName(),9876)).status());
            var current=request(client(),"GET","/api/products/"+own.get(0),null).body().path("data");release.countDown();var reply=reading.get(3,TimeUnit.SECONDS);result(reply,"BASELINE",own);assertEquals(current,reply.body().path("data").path("items").get(0));assertEquals(Boolean.FALSE,observed.insideTransaction.get());
        } finally { release.countDown();executor.shutdownNow();assertTrue(executor.awaitTermination(3,TimeUnit.SECONDS));removeOwned(sql,own); }
    }

    @Test void heldRealHttpSelectionFallsBackAfterAdminDeletesAnOwnedSelectedProduct() throws Exception {
        var sql=control();var admin=loggedIn("ADMIN");var own=new ArrayList<Integer>();var executor=Executors.newSingleThreadExecutor();var arrived=new CountDownLatch(1);var release=new CountDownLatch(1);
        try {
            for(int i=0;i<3;i++)own.add(create(admin,ownName()));
            upstream.handler.set((exchange,payload) -> { arrived.countDown();assertTrue(release.await(4,TimeUnit.SECONDS));upstream.reply(exchange,200,"application/json",upstream.valid(payload),false); });
            var reading=executor.submit(() -> request(client(),"GET",PATH+"?size=2",null));assertTrue(arrived.await(3,TimeUnit.SECONDS));assertEquals(200,request(admin,"DELETE","/api/admin/products/"+own.get(0),null).status());
            release.countDown();result(reading.get(3,TimeUnit.SECONDS),"FALLBACK",own.subList(1,3));assertEquals(Boolean.FALSE,observed.insideTransaction.get());
        } finally { release.countDown();executor.shutdownNow();assertTrue(executor.awaitTermination(3,TimeUnit.SECONDS));removeOwned(sql,own); }
    }

    void selectPermission(JdbcTemplate sql,boolean grant) {
        assertEquals(required("PUBBURI_JPA_RECOMMENDATION_DB_NAME"),required("PUBBURI_PG_DB_NAME"));String role=required("PUBBURI_PG_USER");assertTrue(role.matches("[a-z][a-z0-9_]*"));
        sql.execute((grant?"GRANT SELECT ON pubburi.t_product TO ":"REVOKE SELECT ON pubburi.t_product FROM ")+"\""+role+"\"");
    }
    @Test void initialCurrentAndFallbackDatabaseReadFailuresRemain500InsteadOfEmptySuccess() throws Exception {
        var sql=control();var admin=loggedIn("ADMIN");var own=new ArrayList<Integer>();var executor=Executors.newSingleThreadExecutor();
        try {
            own.add(create(admin,ownName()));
            try { selectPermission(sql,false);assertEquals(500,request(client(),"GET",PATH,null).status());assertEquals(0,upstream.calls.get()); }
            finally { selectPermission(sql,true); }
            for(boolean fallback:List.of(false,true)) {
                var arrived=new CountDownLatch(1);var release=new CountDownLatch(1);
                upstream.handler.set((exchange,payload) -> { arrived.countDown();assertTrue(release.await(4,TimeUnit.SECONDS));upstream.reply(exchange,fallback?503:200,"application/json",upstream.valid(payload),false); });
                try {
                    var reading=executor.submit(() -> request(client(),"GET",PATH,null));assertTrue(arrived.await(3,TimeUnit.SECONDS));selectPermission(sql,false);release.countDown();assertEquals(500,reading.get(3,TimeUnit.SECONDS).status());
                } finally { release.countDown();selectPermission(sql,true); }
            }
            upstream.reset();result(request(client(),"GET",PATH,null),"BASELINE",own);
        } finally { selectPermission(sql,true);executor.shutdownNow();assertTrue(executor.awaitTermination(3,TimeUnit.SECONDS));removeOwned(sql,own); }
    }
}
