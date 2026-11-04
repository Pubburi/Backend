package com.pubburi.pub.recommendation;

import java.time.Instant;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;

public interface ContentCatalogSource {
    Optional<Catalog> current();
    record Binding(int productId,JsonNode product,JsonNode features) { }
    record Catalog(JsonNode catalog,Map<Integer,Binding> bindings,Instant validUntil,String sha256) {
        public List<Integer> ids(){return List.copyOf(bindings.keySet());}
    }
}
