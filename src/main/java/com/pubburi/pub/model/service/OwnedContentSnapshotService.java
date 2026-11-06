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
import com.pubburi.pub.recommendation.ContentCatalogSource.Catalog;
import com.pubburi.pub.recommendation.ContentWire;
import com.pubburi.pub.recommendation.FeedbackSignals;

/** One canonical RR snapshot, with fixed-count scalar batch reads for mapped products. */
@Service
public class OwnedContentSnapshotService {
    public record Facts(JsonNode product,JsonNode features,ProductResponse currentProduct) { }
    public record Snapshot(UserPreferencesResponse preferences, Map<Integer, Facts> products,
            ActivityEvidence activity, FeedbackSignals feedback) {
        public Snapshot(UserPreferencesResponse preferences, Map<Integer, Facts> products, ActivityEvidence activity) {
            this(preferences, products, activity, FeedbackSignals.EMPTY);
        }
    }
    private record ProductProjection(Map<Integer, ProductResponse> current, Map<Integer, Facts> facts) { }
    private final UserPreferenceService preferences;
    private final ProductDao products;
    private final ProductFeatureDao features;
    private final FavoriteDao favorites;
    private final CommentDao comments;
    private final ObjectMapper mapper;
    private final Optional<FeedbackSignals.Reader> feedback;
    public OwnedContentSnapshotService(UserPreferenceService preferences, ProductDao products, ProductFeatureDao features,
            FavoriteDao favorites, CommentDao comments, ObjectMapper mapper, Optional<FeedbackSignals.Reader> feedback) {
        this.preferences = preferences;
        this.products = products;
        this.features = features;
        this.favorites = favorites;
        this.comments = comments;
        this.mapper = mapper;
        this.feedback = feedback;
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Snapshot read(String id,String epoch,List<Integer> ids, List<Integer> activityIds){
        return read(id, epoch, ids, activityIds, false);
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Snapshot read(String id,String epoch,List<Integer> ids, List<Integer> activityIds, boolean includeFeedback){
        var owned=preferences.getOwned(id,epoch);
        if(owned==null)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Account session is no longer current");
        var snapshot = productFacts(ids);
        ActivityEvidence activity = null;
        if (owned.getCategories() == null && !activityIds.isEmpty() && !ids.isEmpty()) {
            var favoriteIds = favorites.selectOwnedProductIds(id, epoch, activityIds);
            var ratedIds = comments.selectOwnedHighlyRatedProductIds(id, epoch, activityIds);
            activity = ActivityEvidence.of(categories(favoriteIds, snapshot.current()),
                categories(ratedIds, snapshot.current()));
        }
        var signals = includeFeedback && !ids.isEmpty()
            ? feedback.map(reader -> reader.readFeedback(id, ids)).orElse(FeedbackSignals.EMPTY)
            : FeedbackSignals.EMPTY;
        return new Snapshot(UserPreferencesResponse.from(owned), snapshot.facts(), activity, signals);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Map<Integer, Facts> readProducts(List<Integer> ids) {
        return productFacts(ids).facts();
    }

    private ProductProjection productFacts(List<Integer> ids) {
        if (ids.isEmpty()) return new ProductProjection(Map.of(), Map.of());
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
        return new ProductProjection(current, Collections.unmodifiableMap(result));
    }

    static boolean matches(Catalog catalog, Map<Integer, Facts> current) {
        if (!current.keySet().equals(catalog.bindings().keySet())) return false;
        for (var binding : catalog.bindings().values()) {
            var facts = current.get(binding.productId());
            if (facts == null || !ContentWire.equal(facts.product(), binding.product())
                    || !ContentWire.equal(facts.features(), binding.features())) return false;
        }
        return true;
    }
    private List<String> categories(List<Integer> ids, Map<Integer, ProductResponse> products) {
        return ids.stream().map(products::get).filter(Objects::nonNull).map(ProductResponse::type)
            .filter(Objects::nonNull).filter(UserPreferences.CATEGORIES::contains).distinct().sorted().toList();
    }
    private JsonNode json(Object value){try{return mapper.readTree(mapper.writeValueAsBytes(value));}catch(java.io.IOException invalid){throw new IllegalStateException("Current projection could not be encoded");}}
}
