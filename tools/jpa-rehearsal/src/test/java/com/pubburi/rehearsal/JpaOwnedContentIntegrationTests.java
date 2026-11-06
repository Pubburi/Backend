package com.pubburi.rehearsal;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.pubburi.pub.controller.response.UserPreferencesResponse;
import com.pubburi.pub.controller.response.ContentRecommendationResponse.CatalogScope;
import com.pubburi.pub.controller.response.ContentRecommendationResponse.ActivityEvidence;
import com.pubburi.pub.model.service.OwnedContentSnapshotService;
import com.pubburi.pub.recommendation.*;
import com.sun.net.httpserver.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.boot.test.context.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.context.annotation.*;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"server.port=0","spring.jpa.properties.hibernate.generate_statistics=true"})
@Import(JpaOwnedContentIntegrationTests.Probes.class)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIfEnvironmentVariable(named="PUBBURI_JPA_INTEGRATION",matches="1")
class JpaOwnedContentIntegrationTests extends JpaAccountTestSupport {
    static final String PATH="/api/users/me/recommendations/content",PREFS="/api/users/me/preferences";
    static Remote remote;
    @Autowired Source source;@Autowired Observed observed;@Autowired OwnedContentSnapshotService snapshots;@Autowired Gate gate;
    @Autowired jakarta.persistence.EntityManagerFactory entityManagerFactory;
    @DynamicPropertySource static void origin(DynamicPropertyRegistry values)throws Exception{remote=new Remote();values.add("pubburi.recommendation.base-url",remote::origin);}
    @BeforeEach void reset(){source.value.set(Optional.empty());remote.calls.set(0);remote.last.set(null);observed.inside.set(null);gate.armed.set(false);remote.handler.set((exchange,body)->remote.reply(exchange,200,zero(body)));}
    @AfterAll static void close(){if(remote!=null)remote.close();}
    class Fixture implements AutoCloseable {
        final java.net.http.HttpClient admin=loggedIn("ADMIN");final String user=join();final java.net.http.HttpClient owner=login(user,password);final List<Integer> ids=new ArrayList<>();
        Fixture()throws Exception{try{for(int i=0;i<2;i++){var body=new HashMap<String,Object>(product(ownName(),1000+i*1000));body.put("type",i==0?"맥주":"증류주");body.put("abv",i*10);var created=request(admin,"POST","/api/admin/products",body);assertEquals(201,created.status());int id=created.body().at("/data/id").intValue();ids.add(id);assertEquals(200,request(admin,"PUT","/api/admin/products/"+id+"/features",Map.of("volumeMl",750-i*250,"tasteTags",i==0?List.of("a,b","line\nx"):List.of(),"aromaTags",List.of(),"sourceName","Authored B1 consumer fixture")).status());}bind(CatalogScope.REVIEWED_DEMO);}catch(Throwable error){close();throw error;}}
        void bind(CatalogScope scope)throws Exception{var catalog=json.createArrayNode();var bindings=new LinkedHashMap<Integer,ContentCatalogSource.Binding>();for(int id:ids){var p=request(owner,"GET","/api/products/"+id,null).body().path("data");var f=request(owner,"GET","/api/products/"+id+"/features",null).body().path("data");var row=catalog.addObject();row.put("product_id",id);row.put("available",true);row.put("sku_group","authored-"+id);row.set("category",p.get("type"));row.set("price_krw",p.get("price"));row.set("abv_percent",p.get("abv"));row.set("volume_ml",f.get("volumeMl"));row.putNull("taste_tags");row.putNull("aroma_tags");bindings.put(id,new ContentCatalogSource.Binding(id,p.deepCopy(),f.deepCopy()));}source.value.set(Optional.of(new ContentCatalogSource.Catalog(catalog,Collections.unmodifiableMap(bindings),Instant.now().plusSeconds(300),"d".repeat(64),scope)));}
        @Override public void close(){source.value.set(Optional.empty());try{for(int id:ids){var deleted=request(admin,"DELETE","/api/admin/products/"+id,null);assertTrue(deleted.status()==200 || deleted.status()==404);}clean(control(),user,null,List.of());}catch(Exception error){throw new IllegalStateException(error);}}
    }
    @Test void strictPrivateInputAndOnlyTheCurrentAccountSnapshotReachTheCheckedCatalog()throws Exception{
        try(var f=new Fixture()){
            var state=accountState(control(),f.user);var identity=sourceIdentity(control());assertEquals(401,request(client(),"POST",PATH,Map.of()).status());assertEquals(0,remote.calls.get());
            var result=request(f.owner,"POST",PATH,Map.of());assertEquals(200,result.status());assertEquals("PROVIDER",result.body().at("/data/source").textValue());assertEquals("REVIEWED_DEMO",result.body().at("/data/catalogScope").textValue());assertEquals(f.ids,result.body().at("/data/products").findValues("id").stream().map(JsonNode::intValue).toList());
            assertEquals(Set.of("catalog","preferences","constraints","limit","include_explanations"),keys(remote.last.get()));assertEquals(f.ids,remote.last.get().path("catalog").findValues("product_id").stream().map(JsonNode::intValue).toList());assertEquals("NOT_STARTED",remote.last.get().at("/preferences/surveyStatus").textValue());assertEquals(4,remote.last.get().path("limit").intValue());assertTrue(remote.last.get().path("include_explanations").booleanValue());assertNull(remote.cookie.get());assertNull(remote.authorization.get());assertEquals(Boolean.FALSE,observed.inside.get());
            int calls=remote.calls.get();for(String body:List.of("{\"userId\":\"other\"}","{\"profile\":{}}","{\"preferences\":{}}","{\"catalog\":[]}","{\"limit\":true}","{\"limit\":\"1\"}","{\"limit\":1.0}","{\"limit\":null}","{\"limit\":0}","{\"limit\":13}"))assertEquals(400,request(f.owner,"POST",PATH,body).status());assertEquals(400,request(f.owner,"POST",PATH+"?userId=other",Map.of()).status());assertEquals(calls,remote.calls.get());
            source.value.set(Optional.empty());var unavailable=request(f.owner,"POST",PATH,Map.of());assertEquals(200,unavailable.status());assertEquals("UNAVAILABLE",unavailable.body().at("/data/source").textValue());assertTrue(unavailable.body().at("/data/result").isNull());assertEquals(0,unavailable.body().at("/data/products").size());assertEquals(calls,remote.calls.get());assertEquals(state,accountState(control(),f.user));assertEquals(identity,sourceIdentity(control()));
        }
    }
    @Test void optInUsesOwnCurrentPositiveSignalsAndDisclosesOnlyReturnedCategoryContributions() throws Exception {
        try (var f = new Fixture()) {
            int beer = f.ids.get(0), spirit = f.ids.get(1);
            String other = join();
            try {
                var otherSession = login(other, password);
                assertEquals(200, request(f.owner, "PUT", "/api/users/me/favorites/" + beer, null).status());
                assertEquals(200, request(f.owner, "PUT", "/api/users/me/favorites/1", null).status());
                assertEquals(200, request(otherSession, "PUT", "/api/users/me/favorites/" + spirit, null).status());
                review(f.owner, spirit, 5);
                int newest = review(f.owner, spirit, 3.99);
                review(otherSession, spirit, 5);
                review(f.owner, 1, 5);
                String epoch = control().queryForObject("SELECT auth_epoch::text FROM pubburi.t_user WHERE id=?", String.class, f.user);
                var stats = entityManagerFactory.unwrap(org.hibernate.SessionFactory.class).getStatistics();
                stats.clear();
                var snapshot = snapshots.read(f.user, epoch, f.ids, f.ids);
                assertEquals(7, stats.getPrepareStatementCount());
                assertEquals(new ActivityEvidence(List.of("맥주"), List.of()), snapshot.activity());

                for (var input : List.of(Map.of(), Map.of("includeActivity", false))) {
                    var ordinary = request(f.owner, "POST", PATH, input);
                    assertEquals("PROVIDER", ordinary.body().at("/data/source").textValue());
                    assertFalse(ordinary.body().path("data").has("activityEvidence"));
                    assertFalse(remote.last.get().has("activity_categories"));
                }
                remote.handler.set((exchange, body) -> remote.reply(exchange, 200, positiveCategory(body, beer)));
                var selected = request(f.owner, "POST", PATH, Map.of("includeActivity", true, "limit", 1));
                assertEquals(200, selected.status());
                assertEquals("PROVIDER", selected.body().at("/data/source").textValue());
                assertEquals(json.valueToTree(Map.of("favoriteCategories", List.of("맥주"), "highlyRatedCategories", List.of())),
                    selected.body().at("/data/activityEvidence"));
                assertEquals(json.valueToTree(Map.of("favorite_categories", List.of("맥주"), "highly_rated_categories", List.of())),
                    remote.last.get().path("activity_categories"));
                assertEquals(request(f.owner, "GET", PREFS, null).body().path("data"), remote.last.get().path("preferences"));
                assertFalse(remote.last.get().toString().contains(f.user));
                assertFalse(remote.last.get().toString().contains("private authored review"));
                assertNull(remote.cookie.get());
                assertNull(remote.authorization.get());

                assertEquals(200, request(f.owner, "PUT", "/api/comments/" + newest, Map.of("rating", 4, "comment", "private authored review")).status());
                var used = request(f.owner, "POST", PATH, Map.of("includeActivity", true, "limit", 1));
                assertEquals("PROVIDER", used.body().at("/data/source").textValue());
                assertEquals(json.valueToTree(List.of("증류주")), remote.last.get().at("/activity_categories/highly_rated_categories"));
                assertEquals(0, used.body().at("/data/activityEvidence/highlyRatedCategories").size());

                remote.handler.set((exchange, body) -> remote.reply(exchange, 200, positiveCategory(body, spirit)));
                var usedReview = request(f.owner, "POST", PATH, Map.of("includeActivity", true, "limit", 1));
                assertEquals("PROVIDER", usedReview.body().at("/data/source").textValue());
                assertEquals("증류주", usedReview.body().at("/data/activityEvidence/highlyRatedCategories/0").textValue());
                assertEquals(0, usedReview.body().at("/data/activityEvidence/favoriteCategories").size());

                assertEquals(200, request(f.owner, "PUT", PREFS, Map.of("surveyStatus", "COMPLETED",
                    "hardConstraints", Map.of("maxPriceKrw", 0))).status());
                remote.handler.set((exchange, body) -> remote.reply(exchange, 200, zero(body).put("supported_profile_tokens", 2)));
                var hard = request(f.owner, "POST", PATH, Map.of("includeActivity", true));
                assertEquals("PROVIDER", hard.body().at("/data/source").textValue());
                assertEquals(0, hard.body().at("/data/result/items").size());
                assertFalse(hard.body().path("data").has("activityEvidence"));
                assertEquals(0, remote.last.get().at("/preferences/hardConstraints/maxPriceKrw").intValue());
                assertTrue(remote.last.get().at("/preferences/softPreferences/categories").isNull());

                assertEquals(200, request(f.owner, "PUT", PREFS, Map.of("surveyStatus", "COMPLETED",
                    "softPreferences", Map.of("categories", List.of()))).status());
                remote.handler.set((exchange, body) -> remote.reply(exchange, 200, zero(body)));
                var explicit = request(f.owner, "POST", PATH, Map.of("includeActivity", true));
                assertEquals("PROVIDER", explicit.body().at("/data/source").textValue());
                assertFalse(explicit.body().path("data").has("activityEvidence"));
                assertFalse(remote.last.get().has("activity_categories"));
                assertEquals(json.createArrayNode(), remote.last.get().at("/preferences/softPreferences/categories"));
                stats.clear();
                assertNull(snapshots.read(f.user, epoch, f.ids, f.ids).activity());
                assertEquals(5, stats.getPrepareStatementCount());
            } finally {
                clean(control(), other, null, List.of());
            }
        }
    }

    @Test void storedNegativeIsOwnedAndScopedAndNeverRetriedWithoutItAfterProviderFailureOrConcurrentClear() throws Exception {
        try (var f = new Fixture()) {
            int excluded = f.ids.get(0), retained = f.ids.get(1);
            String item = "/api/users/me/recommendations/feedback/" + excluded;
            var owner = Map.of("X-Pubburi-Feedback-Owner", f.user);
            String other = join();
            var executor = Executors.newSingleThreadExecutor();
            try {
                var otherSession = login(other, password);
                assertEquals(200, request(f.owner, "PUT", item, Map.of("feedback", "NOT_INTERESTED"), owner).status());
                assertEquals(401, request(otherSession, "POST", PATH, Map.of(), owner).status());
                assertEquals(0, remote.calls.get());
                var legacy = request(f.owner, "POST", PATH, Map.of());
                assertEquals(2, legacy.body().at("/data/products").size());
                assertFalse(remote.last.get().has("feedback"));
                var separate = request(otherSession, "POST", PATH, Map.of(), Map.of("X-Pubburi-Feedback-Owner", other));
                assertEquals(2, separate.body().at("/data/products").size());
                assertFalse(remote.last.get().has("feedback"));
                f.bind(CatalogScope.REVIEWED_FAMILY_BROWSE_DEMO);
                var family = request(f.owner, "POST", PATH, Map.of(), owner);
                assertEquals("REVIEWED_FAMILY_BROWSE_DEMO", family.body().at("/data/catalogScope").textValue());
                assertFalse(remote.last.get().has("feedback"));
                f.bind(CatalogScope.REVIEWED_DEMO);

                remote.handler.set((exchange, body) -> remote.reply(exchange, 200, excludedResult(body)));
                var result = request(f.owner, "POST", PATH, Map.of(), owner);
                assertEquals(200, result.status());
                assertEquals(List.of(retained), result.body().at("/data/products").findValues("id").stream().map(JsonNode::intValue).toList());
                assertEquals(json.valueToTree(Map.of("rankingMode", "profile_fallback",
                    "excludedNotInterestedCount", 1, "positiveReferences", List.of())), result.body().at("/data/feedbackEvidence"));
                assertEquals(json.valueToTree(Map.of("positive_product_ids", List.of(), "not_interested_product_ids", List.of(excluded))),
                    remote.last.get().path("feedback"));
                assertFalse(remote.last.get().toString().contains(f.user));
                assertEquals(Boolean.FALSE, observed.inside.get());

                int calls = remote.calls.get();
                remote.handler.set((exchange, body) -> remote.reply(exchange, 503, "{}"));
                var failed = request(f.owner, "POST", PATH, Map.of(), owner);
                assertEquals("UNAVAILABLE", failed.body().at("/data/source").textValue());
                assertEquals(0, failed.body().at("/data/products").size());
                assertEquals(calls + 1, remote.calls.get());

                var entered = new CountDownLatch(1);
                var release = new CountDownLatch(1);
                try {
                    remote.handler.set((exchange, body) -> {
                        entered.countDown();
                        assertTrue(release.await(4, TimeUnit.SECONDS));
                        remote.reply(exchange, 200, excludedResult(body));
                    });
                    var waiting = executor.submit(() -> request(f.owner, "POST", PATH, Map.of(), owner));
                    assertTrue(entered.await(3, TimeUnit.SECONDS));
                    assertEquals(200, request(login(f.user, password), "DELETE", item, null, owner).status());
                    release.countDown();
                    assertEquals(409, waiting.get(6, TimeUnit.SECONDS).status());
                } finally {
                    release.countDown();
                }
            } finally {
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS));
                clean(control(), other, null, List.of());
            }
        }
    }

    private static ObjectNode excludedResult(JsonNode request) {
        var root = zero(request);
        var catalog = request.path("catalog");
        root.withArray("items").remove(0);
        root.put("eligible_count", 1);
        root.put("feedback_algorithm", "r05-max-explicit-feedback-v1");
        root.put("feedback_group_policy", "exclude_all_known_feedback_groups");
        root.put("ranking_mode", "profile_fallback");
        root.putArray("positive_references");
        root.putArray("not_interested_references").addObject().put("state", "available")
            .setAll(Map.of("product_id", catalog.get(0).get("product_id"), "sku_group", catalog.get(0).get("sku_group")));
        root.put("evaluated_positive_count", 0).put("informative_positive_count", 0)
            .put("excluded_not_interested_count", 1).put("excluded_positive_group_count", 0);
        root.putArray("matches").addObject().put("source", "baseline").putNull("positive_product_id")
            .set("product_id", catalog.get(1).get("product_id"));
        return root;
    }

    @Test void unavailableCatalogRowsAndLateActivityChangesDoNotReturnStaleEvidence() throws Exception {
        try (var f = new Fixture()) {
            int beer = f.ids.get(0), spirit = f.ids.get(1);
            assertEquals(200, request(f.owner, "PUT", "/api/users/me/favorites/" + beer, null).status());
            review(f.owner, spirit, 5);
            var catalog = source.value.get().orElseThrow();
            var candidates = (ArrayNode) catalog.catalog().deepCopy();
            ((ObjectNode) candidates.get(1)).put("available", false);
            source.value.set(Optional.of(new ContentCatalogSource.Catalog(candidates, catalog.bindings(),
                catalog.validUntil(), catalog.sha256(), catalog.scope())));
            var secondSession = login(f.user, password);
            remote.handler.set((exchange, body) -> {
                assertEquals(200, request(secondSession, "DELETE", "/api/users/me/favorites/" + beer, null).status());
                remote.reply(exchange, 503, json.createObjectNode());
            });
            assertEquals(409, request(f.owner, "POST", PATH, Map.of("includeActivity", true)).status());
            assertEquals(json.valueToTree(Map.of("favorite_categories", List.of("맥주"), "highly_rated_categories", List.of())),
                remote.last.get().path("activity_categories"));
            assertEquals(Boolean.FALSE, observed.inside.get());
        }
    }

    private int review(java.net.http.HttpClient owner, int product, double rating) throws Exception {
        var created = request(owner, "POST", "/api/comments", Map.of("productId", product, "rating", rating,
            "comment", "private authored review"));
        assertEquals(201, created.status());
        return created.body().at("/data/id").intValue();
    }

    private ObjectNode positiveCategory(JsonNode body, int id) {
        var root = zero(body);
        var activity = body.path("activity_categories");
        var categories = new HashSet<String>();
        for (String key : List.of("favorite_categories", "highly_rated_categories")) {
            activity.path(key).forEach(category -> categories.add(category.textValue()));
        }
        String category = "";
        for (var product : body.path("catalog")) {
            if (product.path("product_id").intValue() == id) category = product.path("category").textValue();
        }
        root.put("supported_profile_tokens", categories.size());
        root.putNull("fallback_reason");
        var item = root.putArray("items").addObject();
        item.put("product_id", id).put("score", 0.5);
        item.putArray("reasons").add("available").add("content_similarity");
        item.putArray("contributions").addObject().put("component", "text").put("feature", "category:" + category).put("contribution", 0.5);
        var explanation = item.putObject("explanation");
        explanation.put("version", "r04-facts-v1").put("basis", "content_similarity");
        explanation.putObject("comparison").put("kind", "profile");
        explanation.putArray("score_evidence").addObject().put("kind", "category_match").put("label", "주종 일치")
            .put("actual_value", category).put("target_value", category).put("contribution", 0.5);
        explanation.put("total_positive_contributions", 1).put("score_evidence_truncated", false);
        explanation.putArray("filter_evidence").addObject().put("kind", "availability_filter")
            .put("label", "입력 가용 조건 충족").put("actual_value", true);
        explanation.putNull("fallback_reason");
        return root;
    }

    @Test void familyBrowseKeepsUnknownFactsAndRechecksThemAfterRemoteWithoutChangingTheExistingWire()throws Exception{
        try(var f=new Fixture()){
            for(int id:f.ids)assertEquals(200,request(f.admin,"PUT","/api/admin/products/"+id+"/features",Map.of("sourceName","Authored family-browse fixture")).status());
            f.bind(CatalogScope.REVIEWED_FAMILY_BROWSE_DEMO);
            var account=accountState(control(),f.user);var identity=sourceIdentity(control());
            var result=request(f.owner,"POST",PATH,Map.of());assertEquals(200,result.status());
            assertEquals(Set.of("source","catalogScope","result","products"),keys(result.body().path("data")));
            assertEquals("PROVIDER",result.body().at("/data/source").textValue());assertEquals("REVIEWED_FAMILY_BROWSE_DEMO",result.body().at("/data/catalogScope").textValue());
            for(var candidate:remote.last.get().path("catalog"))for(String key:List.of("volume_ml","taste_tags","aroma_tags"))assertTrue(candidate.path(key).isNull());
            for(var product:result.body().at("/data/products")){int id=product.path("id").intValue();assertEquals(request(f.owner,"GET","/api/products/"+id,null).body().path("data"),product);}
            remote.handler.set((exchange,body)->{
                assertEquals(200,request(f.admin,"PUT","/api/admin/products/"+f.ids.get(0)+"/features",Map.of("sourceName","Changed authored family fixture","description","Now known")).status());
                remote.reply(exchange,200,zero(body));
            });
            assertEquals(409,request(f.owner,"POST",PATH,Map.of()).status());assertEquals(Boolean.FALSE,observed.inside.get());
            assertEquals(account,accountState(control(),f.user));assertEquals(identity,sourceIdentity(control()));
        }
    }

    @Test void publicSimilarLinksUseCurrentApprovedProductsAndSuppressDriftOrFamilyOnlyScope() throws Exception {
        try (var f = new Fixture()) {
            int anchor = f.ids.get(0), candidate = f.ids.get(1);
            String path = "/api/products/" + anchor + "/similar";
            var replacement = new HashMap<String, Object>(product(ownName(), 2000));
            replacement.put("type", "맥주");
            replacement.put("abv", 10);
            assertEquals(200, request(f.admin, "PUT", "/api/admin/products/" + candidate, replacement).status());
            f.bind(CatalogScope.REVIEWED_DEMO);
            remote.handler.set((exchange, body) -> remote.reply(exchange, 200, similarPositive(body, candidate)));

            var reply = request(client(), "GET", path, null);
            assertEquals(200, reply.status());
            assertEquals(Set.of("items"), keys(reply.body().path("data")));
            assertEquals(1, reply.body().at("/data/items").size());
            assertEquals(request(client(), "GET", "/api/products/" + candidate, null).body().path("data"),
                reply.body().at("/data/items/0"));
            assertEquals(Set.of("catalog", "reference_product_id", "limit"), keys(remote.last.get()));
            assertEquals(anchor, remote.last.get().path("reference_product_id").intValue());
            assertEquals(4, remote.last.get().path("limit").intValue());
            assertNull(remote.cookie.get());
            assertNull(remote.authorization.get());
            assertEquals(Boolean.FALSE, observed.inside.get());

            remote.handler.set((exchange, body) -> {
                assertEquals(200, request(f.admin, "PUT", "/api/admin/products/" + candidate + "/features",
                    Map.of("volumeMl", 500, "sourceName", "Current authored change")).status());
                remote.reply(exchange, 200, similarPositive(body, candidate));
            });
            assertEquals(json.valueToTree(Map.of("items", List.of())), request(client(), "GET", path, null).body().path("data"));
            int calls = remote.calls.get();
            assertEquals(json.valueToTree(Map.of("items", List.of())), request(client(), "GET", path, null).body().path("data"));
            f.bind(CatalogScope.REVIEWED_FAMILY_BROWSE_DEMO);
            assertEquals(json.valueToTree(Map.of("items", List.of())), request(client(), "GET", path, null).body().path("data"));
            assertEquals(404, request(client(), "GET", "/api/products/0/similar", null).status());
            assertEquals(calls, remote.calls.get());
        }
    }

    private ObjectNode similarPositive(JsonNode request, int candidate) {
        int reference = request.path("reference_product_id").intValue();
        var root = json.createObjectNode().put("algorithm", "b1-tfidf-minmax-v1").put("fit_scope", "request_catalog")
            .put("representation_fitted", true).put("model_fingerprint", "a".repeat(64))
            .put("eligible_count", 1).put("supported_profile_tokens", 1).put("unknown_profile_tokens", 0)
            .putNull("fallback_reason").put("reference_product_id", reference)
            .put("reference_sku_group", "authored-" + reference).put("reference_state", "available")
            .put("profile_source", "reference_product_facts").put("reference_group_policy", "exclude_reference_group")
            .put("excluded_reference_group_count", 1);
        root.putArray("active_numeric_preferences").add("abv_percent").add("volume_ml");
        var item = root.putArray("items").addObject().put("product_id", candidate).put("score", 1.0 / 3);
        item.putArray("reasons").add("available").add("content_similarity");
        item.putArray("contributions").addObject().put("component", "text").put("feature", "category:맥주")
            .put("contribution", 1.0 / 3);
        return root;
    }
    @Test void storedZeroUnknownAndHardBoundsRemainExactAndTamperedFactsNeverBecomeRecommendations()throws Exception{
        try(var f=new Fixture()){
            assertEquals(200,request(f.owner,"PUT",PREFS,"{\"surveyStatus\":\"COMPLETED\",\"softPreferences\":{\"tasteTags\":[],\"aromaTags\":null,\"targetAbvPercent\":0.00},\"hardConstraints\":{\"minPriceKrw\":0,\"maxPriceKrw\":0,\"minAbvPercent\":0.00,\"maxAbvPercent\":10.00}}").status());
            remote.handler.set((exchange,body)->{assertEquals(request(f.owner,"GET",PREFS,null).body().path("data"),body.path("preferences"));assertTrue(body.at("/preferences/softPreferences/aromaTags").isNull());assertTrue(body.at("/preferences/softPreferences/tasteTags").isArray());assertEquals(0,body.at("/preferences/softPreferences/targetAbvPercent").decimalValue().signum());remote.reply(exchange,200,zero(body));});
            // No product meets the exact max-price0 bound; a normal empty provider result remains distinct.
            var state=accountState(control(),f.user);var result=request(f.owner,"POST",PATH,Map.of());assertEquals(200,result.status());assertEquals("PROVIDER",result.body().at("/data/source").textValue());assertEquals(0,result.body().at("/data/result/items").size());assertEquals("no_eligible_products",result.body().at("/data/result/fallback_reason").textValue());
            assertEquals(200,request(f.owner,"PUT",PREFS,Map.of("surveyStatus","NOT_STARTED")).status());state=accountState(control(),f.user);
            for(String mode:List.of("foreign","count","explanation","duplicate","redirect","oversized")){
                remote.handler.set((exchange,body)->{var value=zero(body);if(mode.equals("foreign"))value.withObject("/items/0").put("product_id",1);if(mode.equals("count"))value.put("eligible_count",3);if(mode.equals("explanation"))value.withObject("/items/0/explanation/comparison").put("kind","reference_product");String text=json.writeValueAsString(value);if(mode.equals("duplicate"))text=text.replace("\"algorithm\":","\"algorithm\":\"foreign\",\"algorithm\":");if(mode.equals("oversized"))text+=" ".repeat(ContentHttpClient.MAX_RESPONSE_BYTES);exchange.getResponseHeaders().set("Location","/must-not-follow");remote.reply(exchange,mode.equals("redirect")?302:200,text);});
                int calls=remote.calls.get();var unavailable=request(f.owner,"POST",PATH,Map.of());assertEquals(200,unavailable.status());assertEquals("UNAVAILABLE",unavailable.body().at("/data/source").textValue());assertEquals(calls+1,remote.calls.get());assertEquals(state,accountState(control(),f.user));
            }
        }
    }
    @Test void remoteWaitRechecksAllMappedFactsPreferencesCredentialIncarnationAndSessionLifetime()throws Exception{
        var executor=Executors.newSingleThreadExecutor();
        try{for(String mode:List.of("preferences","price","features","delete","credential","rejoin","logout","otherLogin","name"))try(var f=new Fixture()){
            var entered=new CountDownLatch(1);var release=new CountDownLatch(1);String other=mode.equals("otherLogin")?join():null;
            try{
                remote.handler.set((exchange,body)->{entered.countDown();assertTrue(release.await(4,TimeUnit.SECONDS));remote.reply(exchange,200,zero(body));});var pending=executor.submit(()->request(f.owner,"POST",PATH,Map.of()));assertTrue(entered.await(3,TimeUnit.SECONDS));
                switch(mode){
                    case "preferences"->assertEquals(200,request(login(f.user,password),"PUT",PREFS,Map.of("surveyStatus","COMPLETED","hardConstraints",Map.of("maxPriceKrw",0))).status());
                    case "price"->{var current=json.convertValue(source.value.get().orElseThrow().bindings().get(f.ids.get(0)).product(),new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){});current.remove("id");current.put("price",3000);assertEquals(200,request(f.admin,"PUT","/api/admin/products/"+f.ids.get(0),current).status());}
                    case "features"->assertEquals(200,request(f.admin,"PUT","/api/admin/products/"+f.ids.get(0)+"/features",Map.of("volumeMl",750,"tasteTags",List.of(),"sourceName","Changed authored fact")).status());
                    case "delete"->assertEquals(200,request(f.admin,"DELETE","/api/admin/products/"+f.ids.get(0),null).status());
                    case "credential"->assertEquals(200,request(login(f.user,password),"PUT","/api/users/me/password",Map.of("currentPassword",password,"newPassword","Authored-rotated-42")).status());
                    case "rejoin"->{assertEquals(200,request(f.admin,"DELETE","/api/admin/users/"+f.user,null).status());assertEquals(201,request(client(),"POST","/api/users",registration(f.user)).status());}
                    case "logout"->assertEquals(200,request(f.owner,"POST","/api/auth/logout",null).status());
                    case "otherLogin"->assertEquals(200,request(f.owner,"POST","/api/auth/login",Map.of("id",other,"pass",password)).status());
                    case "name"->assertEquals(200,request(login(f.user,password),"PUT","/api/users/me/profile",Map.of("name","Only current display name")).status());
                }
                release.countDown();int expected=Set.of("preferences","price","features","delete").contains(mode)?409:mode.equals("name")?200:401;assertEquals(expected,pending.get(6,TimeUnit.SECONDS).status(),mode);assertEquals(Boolean.FALSE,observed.inside.get());
            }finally{release.countDown();if(other!=null)clean(control(),other,null,List.of());}
        }}finally{executor.shutdownNow();assertTrue(executor.awaitTermination(3,TimeUnit.SECONDS));}
    }
    @Test void snapshotReadsStayBatchedAndRepeatableAcrossProductAndFeatureCommits()throws Exception{
        try(var f=new Fixture()){
            var stats=entityManagerFactory.unwrap(org.hibernate.SessionFactory.class).getStatistics();stats.clear();var initial=snapshots.read(f.user,control().queryForObject("SELECT auth_epoch::text FROM pubburi.t_user WHERE id=?",String.class,f.user),f.ids,List.of());assertEquals(5,stats.getPrepareStatementCount());assertEquals(2,initial.products().size());
            String epoch=control().queryForObject("SELECT auth_epoch::text FROM pubburi.t_user WHERE id=?",String.class,f.user);var executor=Executors.newSingleThreadExecutor();gate.entered=new CountDownLatch(1);gate.release=new CountDownLatch(1);gate.armed.set(true);
            try{
                var pending=executor.submit(()->snapshots.read(f.user,epoch,f.ids,List.of()));assertTrue(gate.entered.await(3,TimeUnit.SECONDS));var body=new HashMap<String,Object>(product(ownName(),3000));body.put("type","맥주");body.put("abv",0);assertEquals(200,request(f.admin,"PUT","/api/admin/products/"+f.ids.get(0),body).status());assertEquals(200,request(f.admin,"PUT","/api/admin/products/"+f.ids.get(0)+"/features",Map.of("volumeMl",750,"tasteTags",List.of(),"sourceName","New authored tags")).status());gate.release.countDown();var old=pending.get(5,TimeUnit.SECONDS);assertEquals(1000,old.products().get(f.ids.get(0)).product().path("price").intValue());assertEquals(2,old.products().get(f.ids.get(0)).features().path("tasteTags").size());var current=snapshots.read(f.user,epoch,f.ids,List.of());assertEquals(3000,current.products().get(f.ids.get(0)).product().path("price").intValue());assertEquals(0,current.products().get(f.ids.get(0)).features().path("tasteTags").size());
            }finally{gate.armed.set(false);gate.release.countDown();executor.shutdownNow();assertTrue(executor.awaitTermination(3,TimeUnit.SECONDS));}
        }
    }
    @Test void realReadErrorsBeforeOrAfterRemoteRemain500AndDoNotBecomeEmptyUnavailable()throws Exception{
        try(var f=new Fixture()){
            var sql=control();String role=required("PUBBURI_PG_USER");assertTrue(role.matches("[a-z][a-z0-9_]{0,62}"));assertTrue(Boolean.TRUE.equals(sql.queryForObject("SELECT has_table_privilege(?,'pubburi.t_product','SELECT')",Boolean.class,role)));var state=accountState(sql,f.user);var identity=sourceIdentity(sql);
            try{sql.execute("REVOKE SELECT ON pubburi.t_product FROM \""+role+"\"");int calls=remote.calls.get();assertEquals(500,request(f.owner,"POST",PATH,Map.of()).status());assertEquals(calls,remote.calls.get());}finally{sql.execute("GRANT SELECT ON pubburi.t_product TO \""+role+"\"");}
            try{remote.handler.set((exchange,body)->{sql.execute("REVOKE SELECT ON pubburi.t_product FROM \""+role+"\"");remote.reply(exchange,200,zero(body));});assertEquals(500,request(f.owner,"POST",PATH,Map.of()).status());}finally{sql.execute("GRANT SELECT ON pubburi.t_product TO \""+role+"\"");}
            assertEquals(state,accountState(sql,f.user));assertEquals(identity,sourceIdentity(sql));assertEquals(200,request(f.owner,"GET","/api/products/"+f.ids.get(0),null).status());
        }
    }
    static ObjectNode zero(JsonNode request){
        var mapper=new ObjectMapper();var root=mapper.createObjectNode();root.put("algorithm","b1-tfidf-minmax-v1");root.put("fit_scope","request_catalog");root.put("representation_fitted",true);root.put("model_fingerprint","a".repeat(64));var prefs=request.path("preferences");boolean maxZero=prefs.at("/hardConstraints/maxPriceKrw").isNumber() && prefs.at("/hardConstraints/maxPriceKrw").intValue()==0;boolean numeric=prefs.at("/softPreferences/targetAbvPercent").isNumber();
        root.put("eligible_count",maxZero?0:request.path("catalog").size());root.put("supported_profile_tokens",0);root.put("unknown_profile_tokens",0);root.putArray("active_numeric_preferences");if(numeric)root.withArray("active_numeric_preferences").add("abv_percent");root.put("fallback_reason",maxZero?"no_eligible_products":"no_supported_preferences");var items=root.putArray("items");if(!maxZero)for(var row:request.path("catalog")){var item=items.addObject();item.set("product_id",row.get("product_id"));item.put("score",0);item.putArray("reasons").add("available").add("deterministic_baseline");item.putArray("contributions");var e=item.putObject("explanation");e.put("version","r04-facts-v1");e.put("basis","deterministic_baseline");e.putObject("comparison").put("kind","profile");e.putArray("score_evidence");e.put("total_positive_contributions",0);e.put("score_evidence_truncated",false);e.putArray("filter_evidence").addObject().put("kind","availability_filter").put("label","입력 가용 조건 충족").put("actual_value",true);e.put("fallback_reason","no_supported_preferences");}return root;
    }
    static class Source implements ContentCatalogSource {final AtomicReference<Optional<Catalog>> value=new AtomicReference<>(Optional.empty());@Override public Optional<Catalog> current(){return value.get();}}
    static class Observed implements ContentGateway, SimilarProductGateway {
        final ContentHttpClient delegate;
        final AtomicReference<Boolean> inside = new AtomicReference<>();
        Observed(ContentHttpClient delegate) { this.delegate = delegate; }
        @Override public Optional<JsonNode> recommend(JsonNode c, UserPreferencesResponse p, int l, ActivityEvidence activity) {
            inside.set(TransactionSynchronizationManager.isActualTransactionActive());
            return delegate.recommend(c, p, l, activity);
        }
        @Override public Optional<JsonNode> feedback(JsonNode c, UserPreferencesResponse p, int l, ActivityEvidence activity,
                FeedbackSignals signals) {
            inside.set(TransactionSynchronizationManager.isActualTransactionActive());
            return delegate.feedback(c, p, l, activity, signals);
        }
        @Override public Optional<JsonNode> similar(JsonNode catalog, int referenceId, int limit) {
            inside.set(TransactionSynchronizationManager.isActualTransactionActive());
            return delegate.similar(catalog, referenceId, limit);
        }
    }
    static class Gate implements StatementInspector {final AtomicBoolean armed=new AtomicBoolean();volatile CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);@Override public String inspect(String sql){if(sql.contains("left join") && sql.contains("t_product_feature") && armed.compareAndSet(true,false)){entered.countDown();try{if(!release.await(4,TimeUnit.SECONDS))throw new IllegalStateException("Owned RR test gate deadline");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}}return sql;}}
    @TestConfiguration(proxyBeanMethods=false) static class Probes {@Bean @Primary Source source(){return new Source();}@Bean @Primary Observed observed(@Qualifier("contentGateway")ContentHttpClient gateway){return new Observed(gateway);}@Bean Gate gate(){return new Gate();}@Bean HibernatePropertiesCustomizer gates(Gate gate){return properties->properties.put("hibernate.session_factory.statement_inspector",gate);}}
    @FunctionalInterface interface Handler {void handle(HttpExchange exchange,JsonNode body)throws Exception;}
    static class Remote implements AutoCloseable {
        final HttpServer server;final ExecutorService executor=Executors.newCachedThreadPool();final AtomicReference<Handler> handler=new AtomicReference<>();final AtomicInteger calls=new AtomicInteger();final AtomicReference<JsonNode> last=new AtomicReference<>();final AtomicReference<String> cookie=new AtomicReference<>(),authorization=new AtomicReference<>();
        Remote()throws Exception{server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.setExecutor(executor);server.createContext("/",exchange->{try{var body=new ObjectMapper().readTree(exchange.getRequestBody().readAllBytes());assertEquals(body.has("reference_product_id")?"/v1/recommendations/similar":body.has("feedback")?"/v1/recommendations/feedback":"/v1/recommendations/content",exchange.getRequestURI().toString());calls.incrementAndGet();last.set(body);cookie.set(exchange.getRequestHeaders().getFirst("Cookie"));authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));handler.get().handle(exchange,body);}catch(Throwable error){exchange.close();}});server.start();}
        String origin(){return "http://127.0.0.1:"+server.getAddress().getPort();}void reply(HttpExchange exchange,int status,JsonNode body)throws Exception{reply(exchange,status,new ObjectMapper().writeValueAsString(body));}void reply(HttpExchange exchange,int status,String body)throws Exception{byte[] bytes=body.getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(status,bytes.length);try(var output=exchange.getResponseBody()){output.write(bytes);}}@Override public void close(){server.stop(0);executor.shutdownNow();try{assertTrue(executor.awaitTermination(3,TimeUnit.SECONDS));}catch(InterruptedException error){Thread.currentThread().interrupt();throw new IllegalStateException(error);}}
    }
}
