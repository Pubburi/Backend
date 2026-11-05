package com.pubburi.pub.model.service;

import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import com.fasterxml.jackson.databind.*;
import com.pubburi.pub.controller.response.*;
import com.pubburi.pub.controller.response.ContentRecommendationResponse.ActivityEvidence;
import com.pubburi.pub.model.dto.UserPreferences;
import com.pubburi.pub.model.dao.*;

/** One canonical RR snapshot, with fixed-count scalar batch reads for mapped products. */
@Service
public class OwnedContentSnapshotService {
    public record Facts(JsonNode product,JsonNode features,ProductResponse currentProduct) { }
    public record Snapshot(UserPreferencesResponse preferences,Map<Integer,Facts> products, ActivityEvidence activity) { }
    private final UserPreferenceService preferences;
    private final ProductDao products;
    private final ProductFeatureDao features;
    private final FavoriteDao favorites;
    private final CommentDao comments;
    private final ObjectMapper mapper;
    public OwnedContentSnapshotService(UserPreferenceService preferences, ProductDao products, ProductFeatureDao features,
            FavoriteDao favorites, CommentDao comments, ObjectMapper mapper) {
        this.preferences = preferences;
        this.products = products;
        this.features = features;
        this.favorites = favorites;
        this.comments = comments;
        this.mapper = mapper;
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Snapshot read(String id,String epoch,List<Integer> ids, List<Integer> activityIds){
        var owned=preferences.getOwned(id,epoch);
        if(owned==null)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Account session is no longer current");
        if(ids.isEmpty())return new Snapshot(UserPreferencesResponse.from(owned),Map.of(),null);
        var rows=products.selectCurrentByIds(ids);var headers=features.selectByProductIds(ids);var tags=features.selectTagsByProductIds(ids);
        var current=new HashMap<Integer,ProductResponse>();for(var row:rows)if(current.put(row.getId(),ResponseMapper.product(row))!=null)throw new IllegalStateException("Duplicate current product projection");
        var result=new LinkedHashMap<Integer,Facts>();var byProduct=tags.stream().collect(java.util.stream.Collectors.groupingBy(tag->tag.productId()));
        for(var header:headers){
            int productId=header.getProductId();var product=current.get(productId);if(product==null)throw new IllegalStateException("Current product projections disagree");
            var productTags=byProduct.getOrDefault(productId,List.of());
            header.setTasteTags(header.isTasteKnown()?productTags.stream().filter(tag->tag.kind().equals("TASTE")).map(tag->tag.tag()).sorted().toList():null);
            header.setAromaTags(header.isAromaKnown()?productTags.stream().filter(tag->tag.kind().equals("AROMA")).map(tag->tag.tag()).sorted().toList():null);
            if(result.put(productId,new Facts(json(product),json(ProductFeaturesResponse.from(header)),product))!=null)throw new IllegalStateException("Duplicate current feature projection");
        }
        ActivityEvidence activity = null;
        if (owned.getCategories() == null && !activityIds.isEmpty()) {
            var favoriteIds = favorites.selectOwnedProductIds(id, epoch, activityIds);
            var ratedIds = comments.selectOwnedHighlyRatedProductIds(id, epoch, activityIds);
            activity = ActivityEvidence.of(categories(favoriteIds, current), categories(ratedIds, current));
        }
        return new Snapshot(UserPreferencesResponse.from(owned), Collections.unmodifiableMap(result), activity);
    }
    private List<String> categories(List<Integer> ids, Map<Integer, ProductResponse> products) {
        return ids.stream().map(products::get).filter(Objects::nonNull).map(ProductResponse::type)
            .filter(Objects::nonNull).filter(UserPreferences.CATEGORIES::contains).distinct().sorted().toList();
    }
    private JsonNode json(Object value){try{return mapper.readTree(mapper.writeValueAsBytes(value));}catch(java.io.IOException invalid){throw new IllegalStateException("Current projection could not be encoded");}}
}
