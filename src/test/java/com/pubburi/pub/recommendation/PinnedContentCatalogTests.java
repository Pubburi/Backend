package com.pubburi.pub.recommendation;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.pubburi.pub.controller.response.ProductResponse;
import com.pubburi.pub.model.service.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;

/** Local descriptors compose existing checked authority; they do not create source approval. */
class PinnedContentCatalogTests {
    @TempDir Path directory;
    final ObjectMapper json=ContentFixtures.JSON;
    final Clock clock=Clock.fixed(ContentFixtures.NOW,ZoneOffset.UTC);
    static String hash(byte[] bytes)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    ObjectNode bundle(int start,int count)throws Exception{
        var root=ContentFixtures.bundle();var product=root.withArray("catalog").get(0).deepCopy();var record=root.withArray("records").get(0).deepCopy();
        var catalog=root.putArray("catalog");var records=root.putArray("records");
        for(int id=start;id<start+count;id++){
            var p=(ObjectNode)product.deepCopy();p.put("product_id",id);catalog.add(p);
            var r=(ObjectNode)record.deepCopy();r.put("backend_product_id",id).put("catalog_record_id","authored:"+id);
            r.withObject("/product/data").put("id",id);r.withObject("/features/data").put("productId",id);records.add(r);
        }
        return root;
    }
    ObjectNode manifest(List<ObjectNode> bundles)throws Exception{
        var root=json.createObjectNode().put("schema_version",1).put("algorithm",PinnedContentCatalog.SET_ALGORITHM);var members=root.putArray("bundles");
        for(int i=0;i<bundles.size();i++){
            String name="source-"+i+".json";byte[] bytes=json.writeValueAsBytes(bundles.get(i));Files.write(directory.resolve(name),bytes);
            members.addObject().put("path",name).put("sha256",hash(bytes));
        }
        return root;
    }
    PinnedContentCatalog source(ObjectNode root,boolean family)throws Exception{
        byte[] bytes=json.writeValueAsBytes(root);Path file=directory.resolve("manifest.json");Files.write(file,bytes);
        return new PinnedContentCatalog(file.toString(),hash(bytes),json,clock,family);
    }
    @Test void combinesDifferentSourcePinsAgainstOneSnapshotAndKeepsOriginalGroupOrderAndEarliestExpiry()throws Exception{
        var one=bundle(81,1);var two=bundle(82,1);
        two.withObject("/checked_export").put("source_catalog_sha256","d".repeat(64)).put("mapping_sha256","e".repeat(64));
        two.withObject("/checked_export/artifact_sha256").put("receipt.json","f".repeat(64));
        two.put("valid_until_utc",ContentFixtures.NOW.plusSeconds(60).toString());
        var source=source(manifest(List.of(one,two)),false);var catalog=source.current().orElseThrow();
        assertThat(catalog.ids()).containsExactly(81,82);assertThat(catalog.validUntil()).isEqualTo(ContentFixtures.NOW.plusSeconds(60));
        assertThat(catalog.catalog().findValuesAsText("sku_group")).containsExactly("authored-only","authored-only");
        assertThat(catalog.bindings().get(82).product()).isEqualTo(two.at("/records/0/product/data"));
        assertThat(catalog.scope().name()).isEqualTo("REVIEWED_DEMO");
        assertThat(new PinnedContentCatalog(directory.resolve("manifest.json").toString(),catalog.sha256(),json,
            Clock.offset(clock,Duration.ofSeconds(60))).current()).isEmpty();
    }
    @Test void currentCallerMakesOneRequestWithAllApprovedIdsAndRechecksEveryCurrentProduct()throws Exception{
        var source=source(manifest(List.of(bundle(81,1),bundle(82,1))),false);var catalog=source.current().orElseThrow();
        var facts=new LinkedHashMap<Integer,OwnedContentSnapshotService.Facts>();
        for(var b:catalog.bindings().values())facts.put(b.productId(),new OwnedContentSnapshotService.Facts(b.product(),b.features(),json.treeToValue(b.product(),ProductResponse.class)));
        var snapshot=new OwnedContentSnapshotService.Snapshot(ContentFixtures.preferences(),facts,null);
        var snapshots=mock(OwnedContentSnapshotService.class);when(snapshots.read("own","epoch",catalog.ids(),List.of())).thenReturn(snapshot);
        var calls=new AtomicInteger();ContentGateway gateway=(c,p,l,a)->{
            calls.incrementAndGet();assertThat(c).isEqualTo(catalog.catalog());assertThat(l).isEqualTo(1);
            try{return Optional.of(ContentFixtures.response().put("eligible_count",2));}catch(Exception e){throw new AssertionError(e);}
        };
        var service=new OwnedContentRecommendationService(snapshots,source,gateway,json);
        assertThat(service.recommend("own","epoch",1,false).source().name()).isEqualTo("PROVIDER");assertThat(calls.get()).isEqualTo(1);
        var lateChange=new OwnedContentRecommendationService(snapshots,source,(c,p,l,a)->{
            try{Files.writeString(directory.resolve("source-1.json"),"changed after remote call");return Optional.of(ContentFixtures.response().put("eligible_count",2));}
            catch(Exception e){throw new AssertionError(e);}
        },json);
        assertThat(lateChange.recommend("own","epoch",1,false).source().name()).isEqualTo("UNAVAILABLE");
        source(manifest(List.of(bundle(81,1),bundle(82,1))),false);
        when(snapshots.read("own","epoch",catalog.ids(),List.of())).thenReturn(snapshot,
            new OwnedContentSnapshotService.Snapshot(snapshot.preferences(),Map.of(81,facts.get(81)),null));
        assertThatThrownBy(()->service.recommend("own","epoch",1,false)).isInstanceOfSatisfying(ResponseStatusException.class,e->assertThat(e.getStatusCode().value()).isEqualTo(409));
        Files.writeString(directory.resolve("source-1.json"),"changed");
        assertThat(service.recommend("own","epoch",1,false).source().name()).isEqualTo("UNAVAILABLE");assertThat(calls.get()).isEqualTo(2);
    }
    @Test void independentlyValidMembersCannotMixSnapshotsAuthorityScopesDuplicateIdsOrRecordKeys()throws Exception{
        for(Consumer<ObjectNode> mutation:List.<Consumer<ObjectNode>>of(
            v->v.withObject("/checked_export").put("business_snapshot_sha256","d".repeat(64)),
            v->v.withObject("/checked_export").put("snapshot_origin","demo_fixture"),
            v->v.withObject("/checked_export").put("evidence_kind","reviewed_public_source").put("snapshot_origin","existing_local_catalog"),
            v->v.withObject("/records/0").put("catalog_record_id","authored:81"),
            v->{v.withObject("/catalog/0").put("product_id",81);v.withObject("/records/0").put("backend_product_id",81);v.withObject("/records/0/product/data").put("id",81);v.withObject("/records/0/features/data").put("productId",81);})){ 
            var second=bundle(82,1);mutation.accept(second);
            assertThat(PinnedContentCatalog.validate(second,"d".repeat(64),ContentFixtures.NOW)).isNotNull();
            assertThat(source(manifest(List.of(bundle(81,1),second)),true).current()).isEmpty();
        }
        var family=ContentFixtures.familyBundle();
        assertThat(source(manifest(List.of(bundle(82,1),family)),true).current()).isEmpty();
        var secondFamily=family.deepCopy();secondFamily.withObject("/catalog/0").put("product_id",82);secondFamily.withObject("/records/0").put("backend_product_id",82).put("catalog_record_id","authored:82");
        secondFamily.withObject("/records/0/product/data").put("id",82);secondFamily.withObject("/records/0/features/data").put("productId",82);
        var descriptor=manifest(List.of(family,secondFamily));assertThat(source(descriptor,false).current()).isEmpty();
        assertThat(source(descriptor,true).current().orElseThrow().scope().name()).isEqualTo("REVIEWED_FAMILY_BROWSE_DEMO");
    }
    @Test void descriptorAndEveryOriginalFileArePinnedAndAnyMissingTamperedExpiredOrNestedMemberRejectsAll()throws Exception{
        var descriptor=manifest(List.of(bundle(81,1),bundle(82,1)));var source=source(descriptor,false);
        byte[] original=Files.readAllBytes(directory.resolve("source-1.json"));
        Files.writeString(directory.resolve("source-1.json"),new String(original,java.nio.charset.StandardCharsets.UTF_8)+" ");assertThat(source.current()).isEmpty();
        Files.write(directory.resolve("source-1.json"),original);assertThat(source.current()).isPresent();
        Files.writeString(directory.resolve("manifest.json")," "+Files.readString(directory.resolve("manifest.json")));assertThat(source.current()).isEmpty();
        var expired=bundle(82,1);expired.put("valid_until_utc",ContentFixtures.NOW.toString());assertThat(source(manifest(List.of(bundle(81,1),expired)),false).current()).isEmpty();
        var nested=manifest(List.of(bundle(91,1),bundle(92,1)));
        assertThat(source(manifest(List.of(bundle(81,1),nested)),false).current()).isEmpty();
        descriptor=manifest(List.of(bundle(81,1),bundle(82,1)));source=source(descriptor,false);Files.delete(directory.resolve("source-1.json"));assertThat(source.current()).isEmpty();
    }
    @Test void onlyRelativeLocalFilesInsideTheDescriptorTreeAndDistinctPathsAreRead()throws Exception{
        for(String path:List.of("../outside.json",directory.resolve("source-1.json").toString(),"source-0.json")){
            var descriptor=manifest(List.of(bundle(81,1),bundle(82,1)));descriptor.withObject("/bundles/1").put("path",path);
            assertThat(source(descriptor,false).current()).isEmpty();
        }
        var descriptor=manifest(List.of(bundle(81,1),bundle(82,1)));Files.createSymbolicLink(directory.resolve("linked.json"),Path.of("source-1.json"));descriptor.withObject("/bundles/1").put("path","linked.json");assertThat(source(descriptor,false).current()).isEmpty();
        Files.createDirectory(directory.resolve("nested"));Files.move(directory.resolve("source-1.json"),directory.resolve("nested/source.json"));
        descriptor.withObject("/bundles/1").put("path","nested/source.json");assertThat(source(descriptor,false).current()).isPresent();
        Files.createSymbolicLink(directory.resolve("linked-directory"),Path.of("nested"));descriptor.withObject("/bundles/1").put("path","linked-directory/source.json");assertThat(source(descriptor,false).current()).isEmpty();
    }
    @Test void aggregateCandidateCountAndByteBudgetCannotBeMultipliedByTheNumberOfBundles()throws Exception{
        assertThat(source(manifest(List.of(bundle(1,1))),false).current()).isEmpty();
        var many=new ArrayList<ObjectNode>();for(int i=1;i<=PinnedContentCatalog.MAX_BUNDLES+1;i++)many.add(bundle(i,1));
        assertThat(source(manifest(many),false).current()).isEmpty();
        assertThat(source(manifest(List.of(bundle(1,500),bundle(501,500))),false).current().orElseThrow().ids()).hasSize(1000);
        assertThat(source(manifest(List.of(bundle(1,500),bundle(501,501))),false).current()).isEmpty();
        var large=new ArrayList<ObjectNode>();
        for(int i=0;i<3;i++){
            var root=bundle(1+i*200,200);
            for(var record:root.path("records")){
                record.withObject("/product/data").put("name","a".repeat(4999)).put("img","b".repeat(4999));
                record.withObject("/features/data").put("description","c".repeat(4999)).put("sourceName","d".repeat(4999));
            }
            assertThat(json.writeValueAsBytes(root).length).isLessThan(PinnedContentCatalog.MAX_BUNDLE_BYTES);large.add(root);
        }
        assertThat(source(manifest(large),false).current()).isEmpty();
    }
}
