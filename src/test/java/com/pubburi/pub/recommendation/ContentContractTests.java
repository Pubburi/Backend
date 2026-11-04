package com.pubburi.pub.recommendation;

import static org.assertj.core.api.Assertions.*;
import java.time.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.Consumer;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ContentContractTests {
    @TempDir Path directory;
    @Test void pinnedBundleRequiresExactBytesFreshnessExplicitMappingAndCurrentPublicFacts()throws Exception{
        var body=ContentFixtures.bundle();Path file=directory.resolve("bundle.json");byte[] bytes=ContentFixtures.JSON.writeValueAsBytes(body);Files.write(file,bytes);
        String sha=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));var clock=Clock.fixed(ContentFixtures.NOW,ZoneOffset.UTC);
        var source=new PinnedContentCatalog(file.toString(),sha,ContentFixtures.JSON,clock);assertThat(source.current()).isPresent();assertThat(source.current().orElseThrow().ids()).containsExactly(81);
        var authoredMessage=body.deepCopy();authoredMessage.withObject("/records/0/product").put("message","Authored fixture only");assertThat(PinnedContentCatalog.validate(authoredMessage,sha,ContentFixtures.NOW).ids()).containsExactly(81);
        Files.writeString(file," "+Files.readString(file));assertThat(source.current()).isEmpty();Files.write(file,bytes);
        assertThat(new PinnedContentCatalog(file.toString(),sha,ContentFixtures.JSON,Clock.offset(clock,Duration.ofMinutes(5))).current()).isEmpty();
        Files.delete(file);Files.createSymbolicLink(file,Path.of("foreign"));assertThat(source.current()).isEmpty();
        for(Consumer<ObjectNode> mutation:List.<Consumer<ObjectNode>>of(v->v.put("service_catalog_approved",true),v->v.withObject("/checked_export").put("mode","historical_demo_replay"),v->v.withObject("/records/0").put("backend_product_id",82),v->v.withObject("/records/0").put("sku_group","foreign"),v->v.withObject("/catalog/0").put("price_krw",2000),v->v.put("valid_until_utc",ContentFixtures.NOW.minusSeconds(1).toString()),v->v.put("owner","foreign"))){var changed=body.deepCopy();mutation.accept(changed);assertThatThrownBy(()->PinnedContentCatalog.validate(changed,sha,ContentFixtures.NOW)).isInstanceOf(IllegalArgumentException.class);}
    }
    @Test void exactProviderFactsKeepLiteralAtomsWhileUnknownIdsCountsHardFactsAndPrivateReferenceTamperingFailClosed()throws Exception{
        var contract=new ContentContract(ContentFixtures.JSON);var catalog=ContentFixtures.catalog().catalog();var preferences=ContentFixtures.preferences();
        var result=contract.validate(ContentFixtures.response(),catalog,preferences,4);assertThat(result.items().get(0).score()).isEqualByComparingTo("0.5");assertThat(result.items().get(0).explanation().at("/score_evidence/0/actual_value").textValue()).isEqualTo("맥주");
        for(Consumer<ObjectNode> mutation:List.<Consumer<ObjectNode>>of(v->v.withObject("/items/0").put("product_id",1),v->v.put("eligible_count",2),v->v.put("supported_profile_tokens",0),v->v.withObject("/items/0/contributions/0").put("feature","taste:a"),v->v.withObject("/items/0").put("score",0.9),v->v.withObject("/items/0/explanation/comparison").put("kind","reference_product"),v->v.withObject("/items/0/explanation/score_evidence/0").put("target_value","line\nx"),v->v.withObject("/items/0/explanation/filter_evidence/0").put("actual_value",false),v->v.withObject("/items/0/explanation").put("score_evidence_truncated",true),v->v.withArray("/items").add(v.path("items").get(0).deepCopy()))){var invalid=ContentFixtures.response();mutation.accept(invalid);assertThatThrownBy(()->contract.validate(invalid,catalog,preferences,4)).isInstanceOf(IllegalArgumentException.class);}
        var p=new com.pubburi.pub.model.dto.UserPreferences();p.setSurveyStatus("COMPLETED");p.setMaxPriceKrw(0);
        assertThatThrownBy(()->contract.validate(ContentFixtures.response(),catalog,com.pubburi.pub.controller.response.UserPreferencesResponse.from(p),4)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void unsupportedFamilyOriginSourceTagsOrRecordOrderCannotExpandTheContainerDemoAuthority()throws Exception{
        var root=ContentFixtures.twoBundle();assertThat(PinnedContentCatalog.validate(root,"c".repeat(64),ContentFixtures.NOW).ids()).containsExactly(81,82);
        for(Consumer<ObjectNode> mutation:List.<Consumer<ObjectNode>>of(v->v.withObject("/checked_export").put("data_scope","reviewed_source_family_browse_demo_only"),v->v.withObject("/checked_export").put("snapshot_origin","existing_local_catalog"),v->v.withObject("/checked_export").put("evidence_kind","unreviewed"),v->v.withObject("/catalog/0").putArray("taste_tags").add("invented"),v->v.withObject("/catalog/0").putNull("volume_ml"),v->{var records=v.withArray("records");var first=records.get(0);records.set(0,records.get(1));records.set(1,first);})){var invalid=root.deepCopy();mutation.accept(invalid);assertThatThrownBy(()->PinnedContentCatalog.validate(invalid,"c".repeat(64),ContentFixtures.NOW)).isInstanceOf(IllegalArgumentException.class);}
    }
    @Test void mixedZeroScoreItemsKeepNoSharedFactsWithoutInventingPositiveEvidenceOrChangingGlobalFallback()throws Exception{
        var bundle=ContentFixtures.twoBundle();var response=ContentFixtures.response();response.put("eligible_count",2);var second=response.withArray("items").get(0).deepCopy();second.withObject("").put("product_id",82).put("score",0);second.withObject("").putArray("reasons").add("available").add("no_shared_features");second.withObject("").putArray("contributions");var explanation=second.withObject("/explanation");explanation.put("basis","no_shared_features");explanation.putArray("score_evidence");explanation.put("total_positive_contributions",0);response.withArray("items").add(second);
        var result=new ContentContract(ContentFixtures.JSON).validate(response,bundle.path("catalog"),ContentFixtures.preferences(),4);assertThat(result.items()).hasSize(2);assertThat(result.items().get(1).score()).isEqualByComparingTo("0");assertThat(result.fallbackReason()).isNull();assertThat(result.items().get(1).explanation().path("score_evidence")).isEmpty();
    }
    @Test void familyIsExplicitOptInAndOnlyTheFivePublishedScopeOriginEvidenceCombinationsAreAccepted()throws Exception{
        var family=ContentFixtures.familyBundle();
        assertThatThrownBy(()->PinnedContentCatalog.validate(family,"c".repeat(64),ContentFixtures.NOW)).isInstanceOf(IllegalArgumentException.class);
        var approved=PinnedContentCatalog.validate(family,"c".repeat(64),ContentFixtures.NOW,true);
        assertThat(approved.scope().name()).isEqualTo("REVIEWED_FAMILY_BROWSE_DEMO");
        assertThat(approved.catalog().get(0).path("volume_ml").isNull()).isTrue();
        for(String key:List.of("volumeMl","description","tasteTags","aromaTags"))assertThat(approved.bindings().get(81).features().path(key).isNull()).isTrue();
        family.withObject("/checked_export").put("evidence_kind","reviewed_public_source").put("snapshot_origin","owned_family_browse_demo");
        assertThat(PinnedContentCatalog.validate(family,"c".repeat(64),ContentFixtures.NOW,true).scope()).isEqualTo(approved.scope());
        for(String origin:List.of("demo_fixture","owned_demo_fixture","existing_local_catalog","http_read","authored")){
            var wrong=family.deepCopy();wrong.withObject("/checked_export").put("snapshot_origin",origin);
            assertThatThrownBy(()->PinnedContentCatalog.validate(wrong,"c".repeat(64),ContentFixtures.NOW,true)).isInstanceOf(IllegalArgumentException.class);
        }
        for(String origin:List.of("demo_fixture","owned_demo_fixture","existing_local_catalog")){
            var container=ContentFixtures.bundle();container.withObject("/checked_export").put("snapshot_origin",origin).put("evidence_kind",origin.equals("existing_local_catalog")?"reviewed_public_source":"authored_regression_fixture");
            assertThat(PinnedContentCatalog.validate(container,"c".repeat(64),ContentFixtures.NOW,true).scope().name()).isEqualTo("REVIEWED_DEMO");
        }
    }
    @Test void familyScopeCannotMixWithContainerEligibilityOrKnownInventedFactsAndStillUsesFreshPins()throws Exception{
        var root=ContentFixtures.familyBundle();
        for(Consumer<ObjectNode> mutation:List.<Consumer<ObjectNode>>of(
            v->v.withObject("/records/0").put("eligibility_scope","public_browse_row_exists"),
            v->v.withObject("/checked_export").put("data_scope","reviewed_mapped_business_demo_only"),
            v->v.withObject("/checked_export").put("snapshot_origin","owned_demo_fixture"),
            v->v.withObject("/checked_export").put("snapshot_origin","owned_family_browse_demo"),
            v->{v.withObject("/catalog/0").put("volume_ml",750);v.withObject("/records/0/features/data").put("volumeMl",750);},
            v->v.withObject("/records/0/features/data").put("description","invented"),
            v->v.withObject("/records/0/features/data").putArray("tasteTags"),
            v->v.withObject("/records/0/features/data").putArray("aromaTags"),
            v->v.withObject("/catalog/0").putArray("taste_tags"),
            v->v.withObject("/catalog/0").putArray("aroma_tags"),
            v->v.withObject("/catalog/0").put("abv_percent",5.2),
            v->{v.withObject("/catalog/0").putNull("abv_percent");v.withObject("/records/0/product/data").putNull("abv");},
            v->v.put("valid_until_utc",ContentFixtures.NOW.minusSeconds(1).toString()))) {
            var wrong=root.deepCopy();mutation.accept(wrong);
            assertThatThrownBy(()->PinnedContentCatalog.validate(wrong,"c".repeat(64),ContentFixtures.NOW,true)).isInstanceOf(IllegalArgumentException.class);
        }
        Path file=directory.resolve("family.json");byte[] bytes=ContentFixtures.JSON.writeValueAsBytes(root);Files.write(file,bytes);
        String sha=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));var clock=Clock.fixed(ContentFixtures.NOW,ZoneOffset.UTC);
        assertThat(new PinnedContentCatalog(file.toString(),sha,ContentFixtures.JSON,clock,true).current()).isPresent();
        assertThat(new PinnedContentCatalog(file.toString(),sha,ContentFixtures.JSON,clock).current()).isEmpty();
        Files.writeString(file," "+Files.readString(file));assertThat(new PinnedContentCatalog(file.toString(),sha,ContentFixtures.JSON,clock,true).current()).isEmpty();
    }
    @Test void springConfigurationDefaultsToContainerOnlyAndRequiresTheExactFamilyOptInProperty()throws Exception{
        var root=ContentFixtures.familyBundle();var now=Instant.now();root.put("valid_until_utc",now.plusSeconds(240).toString());
        root.withObject("/checked_export").put("checked_at_utc",now.minusSeconds(5).toString()).put("original_export_at_utc",now.minusSeconds(10).toString());
        Path file=directory.resolve("configured-family.json");byte[] bytes=ContentFixtures.JSON.writeValueAsBytes(root);Files.write(file,bytes);String sha=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        var runner=new org.springframework.boot.test.context.runner.ApplicationContextRunner().withUserConfiguration(com.pubburi.pub.config.RecommendationConfig.class)
            .withBean(com.fasterxml.jackson.databind.ObjectMapper.class,()->ContentFixtures.JSON)
            .withPropertyValues("pubburi.recommendation.base-url=http://127.0.0.1:1","pubburi.recommendation.content-catalog-path="+file,"pubburi.recommendation.content-catalog-sha256="+sha);
        runner.run(context->assertThat(context.getBean(ContentCatalogSource.class).current()).isEmpty());
        runner.withPropertyValues("pubburi.recommendation.content-family-browse-demo-enabled=true").run(context->assertThat(context.getBean(ContentCatalogSource.class).current()).hasValueSatisfying(c->assertThat(c.scope().name()).isEqualTo("REVIEWED_FAMILY_BROWSE_DEMO")));
    }

}
