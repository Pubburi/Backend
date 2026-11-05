package com.pubburi.pub.model.service;

import static com.pubburi.pub.recommendation.ContentWire.*;
import java.util.*;
import java.util.stream.Stream;
import com.fasterxml.jackson.databind.JsonNode;
import com.pubburi.pub.controller.response.UserPreferencesResponse;
import com.pubburi.pub.controller.response.UserPreferencesResponse.PreferenceSoftResponse;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pubburi.pub.controller.response.ContentRecommendationResponse;
import com.pubburi.pub.controller.response.ContentRecommendationResponse.*;
import com.pubburi.pub.recommendation.*;

@Service
public class OwnedContentRecommendationService {
    private final OwnedContentSnapshotService snapshots;
    private final ContentCatalogSource catalogs;
    private final ContentGateway gateway;
    private final ContentContract contract;
    private final ObjectMapper mapper;
    public OwnedContentRecommendationService(OwnedContentSnapshotService snapshots,ContentCatalogSource catalogs,ContentGateway gateway,ObjectMapper mapper){this.snapshots=snapshots;this.catalogs=catalogs;this.gateway=gateway;this.mapper=mapper;contract=new ContentContract(mapper);}
    // The two snapshot services own short RR transactions; remote fit/I/O is outside both.
    public ContentRecommendationResponse recommend(String id,String epoch,int limit, boolean includeActivity){
        if(limit<1 || limit>12)throw new IllegalArgumentException("Recommendation limit must be between 1 and 12");
        var approved = catalogs.current();
        var ids = approved.map(ContentCatalogSource.Catalog::ids).orElse(List.of());
        var activityIds = includeActivity && approved.isPresent() ? availableIds(approved.get()) : List.<Integer>of();
        var before = snapshots.read(id, epoch, ids, activityIds);
        if(approved.isEmpty())return ContentRecommendationResponse.unavailable();
        var catalog=approved.get();requireCurrent(catalog,before);
        var response=gateway.recommend(catalog.catalog(),before.preferences(),limit,before.activity());
        var current=snapshots.read(id,epoch,ids,activityIds);
        if(!equal(mapper.valueToTree(before.preferences()),mapper.valueToTree(current.preferences())))changed();
        if (!Objects.equals(before.activity(), current.activity())) changed();
        requireCurrent(catalog,current);
        var after=catalogs.current();
        if(after.isEmpty())return ContentRecommendationResponse.unavailable();
        if(!catalog.sha256().equals(after.get().sha256()))changed();
        if(response.isPresent()){
            try{
                var result=contract.validate(response.get(),catalog.catalog(),effectivePreferences(before),limit);
                return new ContentRecommendationResponse(Source.PROVIDER,catalog.scope(),result,
                    result.items().stream().map(item->current.products().get(item.productId()).currentProduct()).toList(),
                    usedEvidence(before.activity(), result));
            }catch(IllegalArgumentException invalid){/* No local score, replacement catalog or weaker B0 result. */}
        }
        return ContentRecommendationResponse.unavailable();
    }
    private List<Integer> availableIds(ContentCatalogSource.Catalog catalog) {
        var available = new ArrayList<Integer>();
        for (JsonNode product : catalog.catalog()) {
            if (product.path("available").booleanValue()) available.add(product.path("product_id").intValue());
        }
        return available;
    }
    private UserPreferencesResponse effectivePreferences(OwnedContentSnapshotService.Snapshot snapshot) {
        var preferences = snapshot.preferences();
        var activity = snapshot.activity();
        if (activity == null) return preferences;
        var categories = Stream.concat(activity.favoriteCategories().stream(), activity.highlyRatedCategories().stream())
            .distinct().sorted().toList();
        var soft = preferences.softPreferences();
        return new UserPreferencesResponse(preferences.surveyStatus(),
            new PreferenceSoftResponse(categories, soft.tasteTags(), soft.aromaTags(), soft.targetAbvPercent()),
            preferences.hardConstraints());
    }
    private ActivityEvidence usedEvidence(ActivityEvidence activity, Result result) {
        if (activity == null) return null;
        var categories = new HashSet<String>();
        for (var item : result.items()) {
            for (var contribution : item.contributions()) {
                if (contribution.component().equals("text") && contribution.feature().startsWith("category:")) {
                    categories.add(contribution.feature().substring("category:".length()));
                }
            }
        }
        return ActivityEvidence.of(activity.favoriteCategories().stream().filter(categories::contains).toList(),
            activity.highlyRatedCategories().stream().filter(categories::contains).toList());
    }
    private void requireCurrent(ContentCatalogSource.Catalog catalog,OwnedContentSnapshotService.Snapshot current){
        if(!current.products().keySet().equals(catalog.bindings().keySet()))changed();
        for(var binding:catalog.bindings().values()){
            var facts=current.products().get(binding.productId());
            if(facts==null || !equal(facts.product(),binding.product()) || !equal(facts.features(),binding.features()))changed();
        }
    }
    private void changed(){throw new ResponseStatusException(HttpStatus.CONFLICT,"Recommendation inputs changed; request a current reviewed catalog again");}
}
