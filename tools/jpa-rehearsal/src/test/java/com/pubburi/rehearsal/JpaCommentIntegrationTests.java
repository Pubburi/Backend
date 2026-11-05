package com.pubburi.rehearsal;

import static org.junit.jupiter.api.Assertions.*;

import com.pubburi.pub.model.dao.*;
import com.pubburi.pub.model.dto.Comment;
import com.pubburi.pub.model.service.CommentService;
import com.pubburi.pub.controller.api.CommentSort;
import com.pubburi.pub.controller.api.PageCriteria;
import java.math.BigDecimal;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "server.port=0")
@EnabledIfEnvironmentVariable(named = "PUBBURI_JPA_INTEGRATION", matches = "1")
class JpaCommentIntegrationTests extends JpaAccountTestSupport {
    @MockitoSpyBean CommentDao comments;
    @Autowired CommentService service;
    @Autowired UserDao users;
    @Autowired ProductDao products;
    @Autowired DataSource dataSource;
    @Autowired PlatformTransactionManager transactions;
    Map<String, Object> review(int product, float rating, String body) { return Map.of("productId", product, "rating", rating, "comment", body); }
    String path(int product) { return "/api/products/" + product + "/comments"; }

    @Test void fractionalCreateAndBodyEditPreserveWireFieldsOwnershipAndProducerState() throws Exception {
        var sql = control(); var admin = loggedIn("ADMIN"); String user = join(); int product = create(admin, ownName());
        try {
            var owner = login(user, password); var before = state(sql, product); String text = "  authored\n\"review\"\\body  ";
            assertEquals(401, request(client(), "POST", "/api/comments", review(product, 3.7f, text)).status());
            var body = new HashMap<>(review(product, 3.7f, text)); body.put("userId", required("PUBBURI_JPA_ADMIN_ID"));
            var created = request(owner, "POST", "/api/comments", body); assertEquals(201, created.status(), created.body().toString());
            var value = created.body().path("data"); int id = value.path("id").intValue();
            assertTrue(id >= Integer.parseInt(required("PUBBURI_JPA_EXPECTED_COMMENT_NEXT")));
            assertEquals(Set.of("id", "userId", "productId", "rating", "comment"), keys(value));
            assertEquals(user, value.path("userId").textValue()); assertEquals(product, value.path("productId").intValue()); assertEquals(text, value.path("comment").textValue());
            assertEquals(Float.floatToIntBits(3.7f), Float.floatToIntBits(value.path("rating").floatValue()));
            assertEquals(Float.floatToIntBits(3.7f), Float.floatToIntBits(sql.queryForObject("SELECT rating FROM pubburi.t_comment WHERE id=?", Float.class, id)));
            for (var invalid : List.of(review(product, 0.4f, "invalid"), review(product, 5.1f, "invalid"), review(product, 1.25f, " "), review(product, 1.25f, "x".repeat(201))))
                assertEquals(400, request(owner, "POST", "/api/comments", invalid).status());
            var nullBody = new HashMap<String, Object>(); nullBody.put("rating", 2.5); nullBody.put("comment", null);
            assertEquals(400, request(owner, "PUT", "/api/comments/" + id, nullBody).status());
            assertEquals(text, comments.select(id).getComment());
            var changed = request(owner, "PUT", "/api/comments/" + id, Map.of("rating", 1.25, "comment", "가".repeat(200)));
            assertEquals(200, changed.status()); assertEquals(1.25f, changed.body().path("data").path("rating").floatValue());
            assertEquals("가".repeat(200), comments.select(id).getComment());
            assertEquals(before, state(sql, product));
            assertEquals(401, request(client(), "GET", "/api/admin/comments", null).status());
            assertEquals(403, request(owner, "GET", "/api/admin/comments", null).status());
            var page = request(admin, "GET", "/api/admin/comments?size=1", null); assertEquals(200, page.status());
            assertEquals(id, page.body().path("data").path("items").get(0).path("id").intValue());
        } finally { clean(sql, user, product, List.of()); }
    }

    @Test void publicPagesKeepDescIdsNullableHistoryAndMissingProductEmptyVersusFkConflict() throws Exception {
        var sql = control(); var admin = loggedIn("ADMIN"); String user = join(); int product = create(admin, ownName());
        try {
            var owner = login(user, password); var ids = new ArrayList<Integer>();
            for (int i = 1; i <= 3; i++) {
                var reply = request(owner, "POST", "/api/comments", review(product, i + .25f, "authored " + i)); assertEquals(201, reply.status()); ids.add(reply.body().path("data").path("id").intValue());
            }
            int historical = sql.queryForObject("INSERT INTO pubburi.t_comment(user_id,product_id,rating,comment) VALUES(?,?,4.25,NULL) RETURNING id", Integer.class, user, product);
            var first = request(client(), "GET", path(product) + "?size=2", null); assertEquals(200, first.status()); var page = first.body().path("data");
            assertEquals(Set.of("items", "page", "size", "total", "totalPages", "hasNext"), keys(page)); assertEquals(4, page.path("total").intValue());
            assertEquals(historical, page.path("items").get(0).path("id").intValue()); assertTrue(page.path("items").get(0).path("comment").isNull());
            assertEquals(ids.get(2).intValue(), page.path("items").get(1).path("id").intValue());
            var second = request(client(), "GET", path(product) + "?size=2&page=2", null).body().path("data");
            assertEquals(ids.get(1).intValue(), second.path("items").get(0).path("id").intValue()); assertFalse(second.path("hasNext").booleanValue());
            assertEquals(200, request(client(), "GET", path(Integer.MAX_VALUE), null).status());
            assertEquals(0, request(client(), "GET", path(Integer.MAX_VALUE), null).body().path("data").path("total").intValue());
            assertEquals(0, request(client(), "GET", path(0) + "?page=0&size=999", null).body().path("data").path("total").intValue());
            assertEquals(409, request(owner, "POST", "/api/comments", review(Integer.MAX_VALUE, 3.75f, "absent FK")).status());
            assertEquals(4, comments.countByProductId(product)); assertEquals(404, request(client(), "PUT", "/api/comments/2147483647", Map.of("rating", 2.5, "comment", "missing")).status());
        } finally { clean(sql, user, product, List.of()); }
    }

    @Test void ownerAdminAndCurrentCredentialChecksRemainSeparateFromParentCascades() throws Exception {
        var sql = control(); var admin = loggedIn("ADMIN"); String user = join(); String other = join(); int product = create(admin, ownName());
        try {
            var owner = login(user, password); var stranger = login(other, password);
            var created = request(owner, "POST", "/api/comments", review(product, .5f, "owned")); assertEquals(201, created.status()); int id = created.body().path("data").path("id").intValue();
            assertEquals(403, request(stranger, "PUT", "/api/comments/" + id, Map.of("rating", 2.5, "comment", "forbidden")).status());
            assertEquals(403, request(stranger, "DELETE", "/api/comments/" + id, null).status());
            var edit = request(admin, "PUT", "/api/comments/" + id, Map.of("rating", 4.25, "comment", "moderated", "userId", other, "productId", Integer.MAX_VALUE));
            assertEquals(200, edit.status()); assertEquals(user, edit.body().path("data").path("userId").textValue()); assertEquals(product, edit.body().path("data").path("productId").intValue());
            assertEquals(200, request(admin, "PUT", "/api/admin/users/" + user, Map.of("role", "ADMIN")).status());
            assertEquals(200, request(owner, "GET", "/api/admin/comments", null).status());
            assertEquals(200, request(admin, "PUT", "/api/admin/users/" + user, Map.of("role", "USER", "pass", "Authored-comment-fresh")).status());
            assertEquals(401, request(owner, "PUT", "/api/comments/" + id, Map.of("rating", 2.5, "comment", "expired")).status());
            var fresh = login(user, "Authored-comment-fresh"); assertEquals(403, request(fresh, "GET", "/api/admin/comments", null).status());
            assertEquals(200, request(admin, "DELETE", "/api/admin/users/" + user, null).status()); assertNull(comments.select(id));
            assertEquals(401, request(fresh, "POST", "/api/comments", review(product, 1.25f, "expired user")).status());
            var strangerReview = request(stranger, "POST", "/api/comments", review(product, 2.75f, "product cascade")); assertEquals(201, strangerReview.status());
            int cascadeId = strangerReview.body().path("data").path("id").intValue();
            assertEquals(200, request(admin, "DELETE", "/api/admin/products/" + product, null).status()); assertNull(comments.select(cascadeId));
            assertEquals(0, request(client(), "GET", path(product), null).body().path("data").path("total").intValue());
        } finally { clean(sql, user, product, List.of()); clean(sql, other, null, List.of()); }
    }

    @Test void wholeSummaryAndRatingOrderRemainStableAcrossPagesEditsAndDeletes() throws Exception {
        var sql = control(); var admin = loggedIn("ADMIN"); String user = join(); int product = create(admin, ownName());
        try {
            var owner = login(user, password); var ids = new ArrayList<Integer>();
            for (float rating : List.of(4.25f, 1.25f, 4.25f)) ids.add(request(owner, "POST", "/api/comments", review(product, rating, "authored rating sort")).body().path("data").path("id").intValue());
            var original = request(client(), "GET", path(product), null).body().path("data");
            assertEquals(Set.of("items", "page", "size", "total", "totalPages", "hasNext"), keys(original));
            assertEquals(ids.get(2).intValue(), original.path("items").get(0).path("id").intValue());
            var middle = request(client(), "GET", path(product) + "?size=1&page=2&sort=rating_desc&includeSummary=true", null).body().path("data");
            assertEquals(7, middle.size()); assertEquals(ids.get(0).intValue(), middle.path("items").get(0).path("id").intValue());
            assertEquals(3, middle.path("ratingSummary").path("count").intValue());
            assertEquals(0, new BigDecimal("3.25").compareTo(middle.path("ratingSummary").path("average").decimalValue()));
            assertEquals(3, middle.path("totalPages").intValue()); assertTrue(middle.path("hasNext").booleanValue());
            var low = request(client(), "GET", path(product) + "?size=1&sort=rating_asc&includeSummary=false", null).body().path("data");
            assertEquals(6, low.size()); assertEquals(ids.get(1).intValue(), low.path("items").get(0).path("id").intValue());
            var later = request(client(), "GET", path(product) + "?page=99&includeSummary=true", null).body().path("data");
            assertTrue(later.path("items").isEmpty()); assertEquals(middle.path("ratingSummary"), later.path("ratingSummary"));
            assertEquals(200, request(owner, "PUT", "/api/comments/" + ids.get(0), Map.of("rating", 3.75, "comment", "edited")).status());
            var changed = request(client(), "GET", path(product) + "?includeSummary=true", null).body().path("data");
            assertEquals(0, new BigDecimal("3.08").compareTo(changed.path("ratingSummary").path("average").decimalValue()));
            assertEquals(200, request(owner, "DELETE", "/api/comments/" + ids.get(1), null).status());
            changed = request(client(), "GET", path(product) + "?includeSummary=true", null).body().path("data");
            assertEquals(2, changed.path("total").intValue()); assertEquals(2, changed.path("ratingSummary").path("count").intValue());
            assertEquals(0, new BigDecimal("4.00").compareTo(changed.path("ratingSummary").path("average").decimalValue()));
            for (String query : List.of("?sort=nope", "?sort=latest&sort=latest", "?includeSummary=true&includeSummary=true", "?includeSummary=TRUE"))
                assertEquals(400, request(client(), "GET", path(product) + query, null).status());
            assertEquals(6, request(admin, "GET", "/api/admin/comments?includeSummary=true", null).body().path("data").size());
            assertEquals(200, request(admin, "DELETE", "/api/admin/products/" + product, null).status());
            var deleted = request(client(), "GET", path(product) + "?includeSummary=true", null).body().path("data");
            assertEquals(0, deleted.path("total").intValue()); assertEquals(0, deleted.path("ratingSummary").path("count").intValue()); assertTrue(deleted.path("ratingSummary").path("average").isNull());
        } finally { clean(sql, user, product, List.of()); }
    }

    @Test void storedFloatBoundaryAndLegacyValuesUseActualWholeAverageWithoutChangingItems() throws Exception {
        var sql = control(); var admin = loggedIn("ADMIN"); String user = join(); int product = create(admin, ownName());
        try {
            var owner = login(user, password);
            for (float rating : List.of(.58f, .59f)) assertEquals(201, request(owner, "POST", "/api/comments", review(product, rating, "authored boundary")).status());
            var boundary = request(client(), "GET", path(product) + "?includeSummary=true", null).body().path("data");
            assertEquals(.59f, boundary.path("items").get(0).path("rating").floatValue()); assertEquals(.58f, boundary.path("items").get(1).path("rating").floatValue());
            assertEquals(0, new BigDecimal("0.58").compareTo(boundary.path("ratingSummary").path("average").decimalValue()));
            sql.update("DELETE FROM pubburi.t_comment WHERE user_id=? AND product_id=?", user, product);
            sql.update("INSERT INTO pubburi.t_comment(user_id,product_id,rating,comment) VALUES(?,?,5.555,'owned legacy precision fixture')", user, product);
            var legacy = request(client(), "GET", path(product) + "?includeSummary=true", null).body().path("data");
            assertEquals(5.555f, legacy.path("items").get(0).path("rating").floatValue());
            assertEquals(0, new BigDecimal("5.55").compareTo(legacy.path("ratingSummary").path("average").decimalValue()));
            assertEquals(Float.floatToIntBits(5.555f), Float.floatToIntBits(sql.queryForObject("SELECT rating FROM pubburi.t_comment WHERE user_id=? AND product_id=?", Float.class, user, product)));
        } finally { clean(sql, user, product, List.of()); }
    }

    @Test void committedWriterBetweenReadsCannotTearItemsCountAndAverage() throws Exception {
        var sql = control(); var admin = loggedIn("ADMIN"); String user = join(); int product = create(admin, ownName());
        var executor = Executors.newSingleThreadExecutor(); var read = new CountDownLatch(1); var written = new CountDownLatch(1);
        try {
            var owner = login(user, password);
            for (float rating : List.of(1.25f, 3.75f)) assertEquals(201, request(owner, "POST", "/api/comments", review(product, rating, "owned snapshot")).status());
            doAnswer(invocation -> {
                Object rows = invocation.callRealMethod(); read.countDown();
                assertTrue(written.await(5, TimeUnit.SECONDS)); return rows;
            }).when(comments).selectByProductIdPaged(eq(product), anyInt(), anyInt(), any());
            var writer = executor.submit(() -> {
                try {
                    assertTrue(read.await(5, TimeUnit.SECONDS));
                    sql.update("INSERT INTO pubburi.t_comment(user_id,product_id,rating,comment) VALUES(?,?,5,'owned concurrent snapshot')", user, product);
                } finally { written.countDown(); }
                return true;
            });
            var page = service.getCommentsPage(product, PageCriteria.of(1, 10, 10), CommentSort.LATEST, true);
            assertTrue(writer.get(8, TimeUnit.SECONDS)); assertEquals(2, page.items().size()); assertEquals(2, page.total()); assertEquals(2, page.ratingSummary().count());
            assertEquals(0, new BigDecimal("2.50").compareTo(page.ratingSummary().average()));
            assertEquals(3, sql.queryForObject("SELECT COUNT(*) FROM pubburi.t_comment WHERE product_id=?", Integer.class, product));
        } finally {
            reset(comments); written.countDown(); executor.shutdownNow(); assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS)); clean(sql, user, product, List.of());
        }
    }

    @Test void competingWritersKeepCompletePairsAndCompetingDeletesReturnOneSuccess() throws Exception {
        var sql = control(); var admin = loggedIn("ADMIN"); String user = join(); int product = create(admin, ownName()); var executor = Executors.newFixedThreadPool(2);
        try {
            var owner = login(user, password); int id = request(owner, "POST", "/api/comments", review(product, 2.5f, "before")).body().path("data").path("id").intValue();
            var producer = state(sql, product); var start = new CountDownLatch(1);
            var first = executor.submit(() -> { start.await(); return request(owner, "PUT", "/api/comments/" + id, Map.of("rating", 1.25, "comment", "first", "tasteTags", List.of("first"))); });
            var second = executor.submit(() -> { start.await(); return request(admin, "PUT", "/api/comments/" + id, Map.of("rating", 4.75, "comment", "second", "tasteTags", List.of("second"))); });
            start.countDown(); assertEquals(200, first.get(12, TimeUnit.SECONDS).status()); assertEquals(200, second.get(12, TimeUnit.SECONDS).status());
            var row = comments.select(id); assertTrue(Set.of("1.25|first", "4.75|second").contains(row.getRating() + "|" + row.getComment()));
            assertEquals(user, row.getUserId()); assertEquals(product, row.getProductId());
            assertEquals(List.of(row.getComment()), comments.selectTasteTags(List.of(id)).stream().map(x -> x.tag()).toList());
            var next = new CountDownLatch(1); var otherAdmin = loggedIn("ADMIN");
            var deleteOne = executor.submit(() -> { next.await(); return request(admin, "DELETE", "/api/comments/" + id, null).status(); });
            var deleteTwo = executor.submit(() -> { next.await(); return request(otherAdmin, "DELETE", "/api/comments/" + id, null).status(); });
            next.countDown(); assertEquals(Set.of(200, 404), Set.of(deleteOne.get(12, TimeUnit.SECONDS), deleteTwo.get(12, TimeUnit.SECONDS)));
            assertEquals(0, comments.update(row)); assertNull(comments.select(id)); assertEquals(producer, state(sql, product));
        } finally { executor.shutdownNow(); assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS)); clean(sql, user, product, List.of()); }
    }

    @Test void parentFullLocksBlockFkInsertsAndRuntimeCannotRebindOwnersOrResetIdentity() throws Exception {
        var sql = control(); var admin = loggedIn("ADMIN"); String user = join(); int product = create(admin, ownName()); var executor = Executors.newSingleThreadExecutor();
        try {
            for (String parent : List.of("user", "product")) new TransactionTemplate(transactions).execute(status -> {
                if (parent.equals("user")) assertNotNull(users.selectByIdForUpdate(user)); else assertNotNull(products.selectByIdForUpdate(product));
                var result = executor.submit(() -> {
                    try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
                        connection.setAutoCommit(false); statement.execute("SET LOCAL lock_timeout='250ms'");
                        try (var insert = connection.prepareStatement("INSERT INTO pubburi.t_comment(user_id,product_id,rating,comment) VALUES(?,?,3.7,'authored lock')")) {
                            insert.setString(1, user); insert.setInt(2, product);
                            try { insert.executeUpdate(); return "unexpected_success"; } catch (SQLException error) { return error.getSQLState(); }
                            finally { connection.rollback(); }
                        }
                    }
                });
                try { assertEquals("55P03", result.get(3, TimeUnit.SECONDS)); } catch (Exception error) { throw new IllegalStateException("owned comment FK experiment failed", error); }
                return null;
            });
            for (String denied : List.of("UPDATE pubburi.t_comment SET id=id WHERE false", "UPDATE pubburi.t_comment SET user_id=user_id WHERE false", "UPDATE pubburi.t_comment SET product_id=product_id WHERE false", "TRUNCATE pubburi.t_comment", "UPDATE pubburi.t_comment_taste_tag SET tag=tag WHERE false", "TRUNCATE pubburi.t_comment_taste_tag", "SELECT setval('pubburi.t_comment_id_seq',1)")) {
                var error = assertThrows(DataAccessException.class, () -> runtime.execute(denied)); assertEquals("42501", ((SQLException) error.getMostSpecificCause()).getSQLState());
            }
        } finally { executor.shutdownNow(); assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS)); clean(sql, user, product, List.of()); }
    }
    @Test void memberTasteOpinionsAreOptionalNormalizedAtomicAndOwnerScoped() throws Exception {
        var sql=control();var admin=loggedIn("ADMIN");String user=join(),other=join();int product=create(admin,ownName());
        try {
            var owner=login(user,password);var stranger=login(other,password);
            var payload=new HashMap<String,Object>(review(product,3.75f,"tagged"));payload.put("tasteTags",List.of(" SWEET ","과일,향\n산미","Ａ","İ"));
            var created=request(owner,"POST","/api/comments",payload);assertEquals(201,created.status(),created.body().toString());var item=created.body().path("data");int id=item.path("id").intValue();assertEquals(5,item.size());
            List<String> expected=List.of("i\u0307","sweet","과일,향\n산미","ａ");
            var page=request(client(),"GET",path(product)+"?includeTasteTags=true&includeSummary=true&size=1&sort=rating_desc",null).body().path("data");
            assertEquals(7,page.size());assertEquals(expected,new com.fasterxml.jackson.databind.ObjectMapper().convertValue(page.path("items").get(0).path("tasteTags"),List.class));assertEquals(3.75f,page.path("items").get(0).path("rating").floatValue());
            for(String option:List.of("","?includeTasteTags=false")) assertEquals(5,request(client(),"GET",path(product)+option,null).body().path("data").path("items").get(0).size());
            assertEquals(5,request(admin,"GET","/api/admin/comments?includeTasteTags=true&size=1",null).body().path("data").path("items").get(0).size());
            for(Object invalid:Arrays.asList(null,List.of(1),List.of("SWEET","sweet"),List.of(" "),List.of("x".repeat(41)),List.of("nul\u0000tag"),Collections.nCopies(21,"x"))) {
                var change=new HashMap<String,Object>();change.put("rating",1.25);change.put("comment","must not change");change.put("tasteTags",invalid);
                assertEquals(400,request(owner,"PUT","/api/comments/"+id,change).status());
            }
            assertEquals("tagged",comments.select(id).getComment());assertEquals(expected,comments.selectTasteTags(List.of(id)).stream().map(x->x.tag()).sorted().toList());
            assertEquals(403,request(stranger,"PUT","/api/comments/"+id,Map.of("rating",1.25,"comment","forbidden","tasteTags",List.of("forbidden"))).status());
            assertEquals(200,request(owner,"PUT","/api/comments/"+id,Map.of("rating",1.25,"comment","omission preserves")).status());assertEquals(expected,comments.selectTasteTags(List.of(id)).stream().map(x->x.tag()).sorted().toList());
            assertEquals(200,request(admin,"PUT","/api/comments/"+id,Map.of("rating",2.75,"comment","moderated","tasteTags",List.of(" DRY "))).status());assertEquals(List.of("dry"),comments.selectTasteTags(List.of(id)).stream().map(x->x.tag()).toList());
            assertEquals(200,request(owner,"PUT","/api/comments/"+id,Map.of("rating",2.75,"comment","clear","tasteTags",List.of())).status());assertTrue(comments.selectTasteTags(List.of(id)).isEmpty());
            assertEquals(200,request(owner,"PUT","/api/comments/"+id,Map.of("rating",2.75,"comment","cascade","tasteTags",List.of("단맛"))).status());
            assertEquals(200,request(admin,"DELETE","/api/admin/users/"+user,null).status());assertTrue(comments.selectTasteTags(List.of(id)).isEmpty());
            var next=request(stranger,"POST","/api/comments",Map.of("productId",product,"rating",1.25,"comment","parent","tasteTags",List.of("산미")));int nextId=next.body().path("data").path("id").intValue();
            assertEquals(200,request(admin,"DELETE","/api/admin/products/"+product,null).status());assertTrue(comments.selectTasteTags(List.of(nextId)).isEmpty());
        } finally {clean(sql,user,product,List.of());clean(sql,other,null,List.of());}
    }

    @Test void committedTagReplacementBetweenPageAndBatchReadKeepsOneSnapshot() throws Exception {
        var sql=control();var admin=loggedIn("ADMIN");String user=join();int product=create(admin,ownName());
        var executor=Executors.newSingleThreadExecutor();var read=new CountDownLatch(1);var written=new CountDownLatch(1);
        try {
            var owner=login(user,password);int id=request(owner,"POST","/api/comments",Map.of("productId",product,"rating",1.25,"comment","before","tasteTags",List.of("before"))).body().path("data").path("id").intValue();
            doAnswer(call->{Object rows=call.callRealMethod();read.countDown();assertTrue(written.await(5,TimeUnit.SECONDS));return rows;}).when(comments).selectByProductIdPaged(eq(product),anyInt(),anyInt(),any());
            var writer=executor.submit(()->{try {assertTrue(read.await(5,TimeUnit.SECONDS));assertEquals(200,request(owner,"PUT","/api/comments/"+id,Map.of("rating",4.75,"comment","after","tasteTags",List.of("after"))).status());}finally{written.countDown();}return true;});
            var page=service.getCommentsPage(product,PageCriteria.of(1,10,10),CommentSort.LATEST,true,true);assertTrue(writer.get(8,TimeUnit.SECONDS));
            assertEquals("before",page.items().get(0).getComment());assertEquals(List.of("before"),page.items().get(0).getTasteTags());assertEquals(0,new BigDecimal("1.25").compareTo(page.ratingSummary().average()));
            assertEquals("after",sql.queryForObject("SELECT tag FROM pubburi.t_comment_taste_tag WHERE comment_id=?",String.class,id));
        } finally {reset(comments);written.countDown();executor.shutdownNow();assertTrue(executor.awaitTermination(3,TimeUnit.SECONDS));clean(sql,user,product,List.of());}
    }

    @Test void rawEscapedSurrogatesCannotCreateOrReplaceReviewOpinions() throws Exception {
        var sql=control();var admin=loggedIn("ADMIN");String user=join();int product=create(admin,ownName());
        try {
            var owner=login(user,password);
            String valid="{\"productId\":"+product+",\"rating\":3.75,\"comment\":\"paired\",\"tasteTags\":[\"\\ud83c\\udf3e\"]}";
            assertTrue(valid.chars().allMatch(value->value<128));assertTrue(valid.contains("\\ud83c\\udf3e"));
            var created=request(owner,"POST","/api/comments",valid);assertEquals(201,created.status());int id=created.body().path("data").path("id").intValue();
            var before=request(client(),"GET",path(product)+"?includeTasteTags=true&includeSummary=true",null).body().path("data");
            assertEquals("🌾",before.path("items").get(0).path("tasteTags").get(0).textValue());
            for(String escape:List.of("\\ud800","\\udfff")) {
                String raw="{\"productId\":"+product+",\"rating\":1.25,\"comment\":\"must not change\",\"tasteTags\":[\""+escape+"\"]}";
                assertTrue(raw.chars().allMatch(value->value<128));assertTrue(raw.contains(escape));
                assertEquals(400,request(owner,"POST","/api/comments",raw).status());
                assertEquals(400,request(owner,"PUT","/api/comments/"+id,raw.replace("\"productId\":"+product+",","")).status());
                assertEquals(before,request(client(),"GET",path(product)+"?includeTasteTags=true&includeSummary=true",null).body().path("data"));
            }
        } finally {clean(sql,user,product,List.of());}
    }

}
