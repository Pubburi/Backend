package com.pubburi.pub.recommendation;

import java.math.BigDecimal;
import java.util.*;
import com.fasterxml.jackson.databind.node.*;
import com.pubburi.pub.controller.response.ContentRecommendationResponse.CatalogScope;
import com.pubburi.pub.model.service.OwnedContentSnapshotService.Facts;

/** Stable reviewed facts; only resolve() may supply current business price/row availability. */
public record ReviewedLocalCatalog(List<Product> records,String sha256,String businessSourceId)
        implements ContentCatalogSource.Authority {
    public record Product(int productId,String skuGroup,String label,String category,BigDecimal abvPercent,int volumeMl){ }
    public ReviewedLocalCatalog { records=List.copyOf(records); }
    @Override public List<Integer> ids(){return records.stream().map(Product::productId).toList();}
    @Override public CatalogScope scope(){return CatalogScope.REVIEWED_LOCAL_CATALOG;}
    @Override public Optional<ContentCatalogSource.Catalog> resolve(Map<Integer,Facts> current){
        if(!new HashSet<>(ids()).containsAll(current.keySet()))return Optional.empty();
        var catalog=JsonNodeFactory.instance.arrayNode();var bindings=new LinkedHashMap<Integer,ContentCatalogSource.Binding>();
        for(var record:records){
            var facts=current.get(record.productId());
            if(facts!=null){
                var product=facts.product();var features=facts.features();
                if(product==null || features==null || !features.isObject()
                        || !ContentWire.equal(product.get("id"),IntNode.valueOf(record.productId())) || !ContentWire.equal(features.get("productId"),IntNode.valueOf(record.productId()))
                        || !ContentWire.text(product.get("name"),record.label()) || !ContentWire.text(product.get("type"),record.category())
                        || !ContentWire.equal(product.get("abv"),DecimalNode.valueOf(record.abvPercent()))
                        || !ContentWire.equal(features.get("volumeMl"),IntNode.valueOf(record.volumeMl()))
                        || !product.path("price").isIntegralNumber() || !product.path("price").canConvertToInt() || product.path("price").intValue()<0)return Optional.empty();
                bindings.put(record.productId(),new ContentCatalogSource.Binding(record.productId(),product.deepCopy(),features.deepCopy()));
            }
            var row=catalog.addObject().put("product_id",record.productId()).put("available",facts!=null)
                .put("category",record.category()).put("sku_group",record.skuGroup()).put("volume_ml",record.volumeMl());
            row.set("abv_percent",DecimalNode.valueOf(record.abvPercent()));
            if(facts==null)row.putNull("price_krw");else row.set("price_krw",facts.product().get("price"));
            // Current DB tags/media/description are not source authority and never enter this model catalog.
            row.putNull("taste_tags").putNull("aroma_tags");
        }
        // Stable authority has no fabricated expiration; the original DEMO type retains its own TTL.
        return Optional.of(new ContentCatalogSource.Catalog(catalog,Collections.unmodifiableMap(bindings),null,sha256,scope()));
    }
}
