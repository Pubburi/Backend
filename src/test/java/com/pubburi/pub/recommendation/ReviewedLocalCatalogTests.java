package com.pubburi.pub.recommendation;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pubburi.pub.model.service.OwnedContentSnapshotService;
import org.junit.jupiter.api.Test;

class ReviewedLocalCatalogTests {
    @Test void currentPriceAndRealDeletionAreSeparateFromStableKnownFacts()throws Exception{
        var authority=ReviewedLocalFixtures.catalog();var current=ReviewedLocalFixtures.current();
        var old=current.get(81);var p=old.currentProduct();var updated=new com.pubburi.pub.controller.response.ProductResponse(p.id(),p.name(),p.type(),7000,p.img(),p.abv(),p.orderCount());
        current.put(81,new OwnedContentSnapshotService.Facts(ReviewedLocalFixtures.JSON.valueToTree(updated),old.features(),updated));
        var first=authority.resolve(current).orElseThrow();assertEquals(7000,first.catalog().get(0).path("price_krw").intValue());assertTrue(first.catalog().get(0).path("taste_tags").isNull());assertFalse(first.catalog().get(0).has("description"));assertTrue(first.catalog().get(0).path("aroma_tags").isNull());
        current.remove(82);var deleted=authority.resolve(current).orElseThrow();assertEquals(List.of(81,82),authority.ids());assertEquals(List.of(81),deleted.ids());assertFalse(deleted.catalog().get(1).path("available").booleanValue());assertTrue(deleted.catalog().get(1).path("price_krw").isNull());assertEquals(500,deleted.catalog().get(1).path("volume_ml").intValue());assertTrue(OwnedContentSnapshotService.matches(deleted,current));
        current.put(82,ReviewedLocalFixtures.current().get(82));assertFalse(OwnedContentSnapshotService.matches(deleted,current));
    }
    @Test void identityTypeAbvAndKnownVolumeMismatchCannotBecomeUnavailableRows()throws Exception{
        for(String field:List.of("name","type","abv","volumeMl","missingFeatures")){
            var current=ReviewedLocalFixtures.current();var original=current.get(81);
            switch(field){
                case "name"->((ObjectNode)original.product()).put("name","Different identity");
                case "type"->((ObjectNode)original.product()).put("type","탁주");
                case "abv"->((ObjectNode)original.product()).put("abv",10);
                case "volumeMl"->((ObjectNode)original.features()).put("volumeMl",500);
                case "missingFeatures"->current.put(81,new OwnedContentSnapshotService.Facts(original.product(),ReviewedLocalFixtures.JSON.nullNode(),original.currentProduct()));
            }
            assertTrue(ReviewedLocalFixtures.catalog().resolve(current).isEmpty(),field);
        }
    }
}
