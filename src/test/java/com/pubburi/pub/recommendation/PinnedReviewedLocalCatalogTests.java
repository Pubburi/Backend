package com.pubburi.pub.recommendation;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.function.Consumer;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PinnedReviewedLocalCatalogTests {
    @TempDir Path directory;
    String sha(byte[] value)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));}
    @Test void twoOriginalPinsHaveNoBusinessTtlAndRawMutationIsNeverAdopted()throws Exception{
        byte[] original=ReviewedLocalFixtures.JSON.writeValueAsBytes(ReviewedLocalFixtures.authority());String authoritySha=sha(original);
        byte[] mapping=ReviewedLocalFixtures.JSON.writeValueAsBytes(ReviewedLocalFixtures.mapping(authoritySha));
        Path source=directory.resolve("authority.json"),bindings=directory.resolve("mapping.json");Files.write(source,original);Files.write(bindings,mapping);
        var loader=new PinnedReviewedLocalCatalog(source.toString(),authoritySha,bindings.toString(),sha(mapping),ReviewedLocalFixtures.JSON,Clock.fixed(ReviewedLocalFixtures.NOW.plusSeconds(86400),ZoneOffset.UTC));
        var authority=loader.current().orElseThrow();assertEquals(List.of(81,82),authority.ids());assertEquals(ReviewedLocalFixtures.NAMESPACE,authority.businessSourceId());
        assertNull(authority.resolve(ReviewedLocalFixtures.current()).orElseThrow().validUntil());
        Files.writeString(source,new String(original,java.nio.charset.StandardCharsets.UTF_8)+" ");assertTrue(loader.current().isEmpty());Files.write(source,original);
        Files.writeString(bindings,new String(mapping,java.nio.charset.StandardCharsets.UTF_8)+" ");assertTrue(loader.current().isEmpty());
    }
    @Test void typedProvenanceNamesUnitsLicenseNullsDatesAndMappingsFailClosed()throws Exception{
        var root=ReviewedLocalFixtures.authority();var mapping=ReviewedLocalFixtures.mapping("a".repeat(64));
        assertEquals(2,PinnedReviewedLocalCatalog.validate(root,mapping,"a".repeat(64),"b".repeat(64),ReviewedLocalFixtures.NOW).ids().size());
        for(Consumer<ObjectNode> mutation:List.<Consumer<ObjectNode>>of(
            value->value.put("service_catalog_approved",true),value->value.put("valid_until_utc",ReviewedLocalFixtures.NOW.plusSeconds(300).toString()),
            value->value.put("reviewed_at_utc",ReviewedLocalFixtures.NOW.plusSeconds(1).toString()),
            value->value.withObject("/source").put("evidence_kind","authored_regression_fixture"),
            value->value.withObject("/source").put("source_version","20260230"),value->value.withObject("/source").put("source_version","2026-07-31"),
            value->value.withObject("/records/0").put("source_record_id","Q123"),
            value->value.withObject("/records/0").put("source_row_number",2),
            value->value.withObject("/license").put("source_media_included",true),
            value->value.withObject("/records/0/facts").put("volume_ml",0),value->value.withObject("/records/0/facts").put("category","탁주"),
            value->value.withObject("/records/0/facts").put("image_url","https://unreviewed.invalid/image.png"),
            value->value.withObject("/records/0/facts").putArray("taste_tags"),
            value->value.withObject("/records/0/evidence").put("url","https://unreviewed.invalid/front/publication/M000000066/view.do"),
            value->value.withObject("/records/0/evidence").put("url","https://thesool.com:443/front/publication/M000000066/view.do"),
            value->value.withObject("/records/0/evidence").put("url","https://thesool.com/other")
        )){var invalid=root.deepCopy();mutation.accept(invalid);assertThrows(Exception.class,()->PinnedReviewedLocalCatalog.validate(invalid,mapping,"a".repeat(64),"b".repeat(64),ReviewedLocalFixtures.NOW));}
        for(Consumer<ObjectNode> mutation:List.<Consumer<ObjectNode>>of(value->value.put("authority_sha256","c".repeat(64)),value->value.put("catalog_version","other"),value->value.put("source_version","20260730"),value->value.put("business_source_id","00000000-0-4000-8000-1"),value->value.withObject("/records/1").put("backend_product_id",81),value->value.withObject("/records/0").put("sku_group","guessed"))){var invalid=mapping.deepCopy();mutation.accept(invalid);assertThrows(Exception.class,()->PinnedReviewedLocalCatalog.validate(root,invalid,"a".repeat(64),"b".repeat(64),ReviewedLocalFixtures.NOW));}
    }
}
