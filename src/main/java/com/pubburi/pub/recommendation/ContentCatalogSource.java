package com.pubburi.pub.recommendation;

import java.time.Instant;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.pubburi.pub.controller.response.ContentRecommendationResponse.CatalogScope;
import com.pubburi.pub.model.service.OwnedContentSnapshotService;

public interface ContentCatalogSource {
    Optional<? extends Authority> current();
    sealed interface Authority permits Catalog,ReviewedLocalCatalog {
        List<Integer> ids();
        String sha256();
        CatalogScope scope();
        default String businessSourceId(){return null;}
        Optional<Catalog> resolve(Map<Integer,OwnedContentSnapshotService.Facts> current);
    }
    record Binding(int productId,JsonNode product,JsonNode features) { }
    record Catalog(JsonNode catalog,Map<Integer,Binding> bindings,Instant validUntil,String sha256,CatalogScope scope) implements Authority {
        public List<Integer> ids(){return List.copyOf(bindings.keySet());}
        @Override public Optional<Catalog> resolve(Map<Integer,OwnedContentSnapshotService.Facts> current){
            return OwnedContentSnapshotService.matches(this,current)?Optional.of(this):Optional.empty();
        }
    }
}
