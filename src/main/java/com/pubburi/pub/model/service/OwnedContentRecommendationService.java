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
    private final FeedbackContract feedbackContract;
    private final ObjectMapper mapper;
    public OwnedContentRecommendationService(OwnedContentSnapshotService snapshots, ContentCatalogSource catalogs,
            ContentGateway gateway, ObjectMapper mapper) {
        this.snapshots = snapshots;
        this.catalogs = catalogs;
        this.gateway = gateway;
        this.mapper = mapper;
        contract = new ContentContract(mapper);
        feedbackContract = new FeedbackContract(contract);
    }
    public ContentRecommendationResponse recommend(String id, String epoch, int limit, boolean includeActivity) {
        return recommend(id, epoch, limit, includeActivity, false);
    }

    // The two snapshots own short RR transactions; the provider call stays outside both.
    public ContentRecommendationResponse recommend(String id, String epoch, int limit, boolean includeActivity,
            boolean includeFeedback) {
        if (limit < 1 || limit > 12) throw new IllegalArgumentException("Recommendation limit must be between 1 and 12");
        var approved = catalogs.current();
        var ids = approved.map(ContentCatalogSource.Authority::ids).orElse(List.of());
        var activityIds = includeActivity && approved.isPresent() ? approved.get() instanceof ContentCatalogSource.Catalog catalog
            ? availableIds(catalog) : ids : List.<Integer>of();
        boolean useFeedback = includeFeedback && approved.map(catalog -> Set.of(CatalogScope.REVIEWED_DEMO,CatalogScope.REVIEWED_LOCAL_CATALOG).contains(catalog.scope())).orElse(false);
        var namespace=approved.map(ContentCatalogSource.Authority::businessSourceId).orElse(null);
        var before = snapshot(id, epoch, ids, activityIds, useFeedback,namespace);
        if (approved.isEmpty()) return ContentRecommendationResponse.unavailable();

        var authority = approved.get();
        var catalog = authority.resolve(before.products()).orElseThrow(()->new ResponseStatusException(HttpStatus.CONFLICT,"Reviewed catalog facts changed"));
        var signals = before.feedback();
        if (!signals.withinProviderLimit()) return unavailable(catalog.scope());
        var response = signals.isEmpty()
            ? gateway.recommend(catalog.catalog(), before.preferences(), limit, before.activity())
            : gateway.feedback(catalog.catalog(), before.preferences(), limit, before.activity(), signals);

        var current = snapshot(id, epoch, ids, activityIds, useFeedback,namespace);
        if (!equal(mapper.valueToTree(before.preferences()), mapper.valueToTree(current.preferences()))
                || !Objects.equals(before.activity(), current.activity()) || !signals.equals(current.feedback())) changed();
        requireCurrent(catalog, current);
        var after = catalogs.current();
        if (after.isEmpty()) {if(authority.scope()==CatalogScope.REVIEWED_LOCAL_CATALOG)changed();return ContentRecommendationResponse.unavailable();}
        if (!catalog.sha256().equals(after.get().sha256())) changed();
        if (response.isEmpty()) return unavailable(catalog.scope());

        try {
            var preferences = effectivePreferences(before);
            Result result;
            FeedbackEvidence feedbackEvidence = null;
            if (signals.isEmpty()) {
                result = contract.validate(response.get(), catalog.catalog(), preferences, limit);
            } else {
                var checked = feedbackContract.validate(response.get(), catalog.catalog(), preferences, limit, signals);
                result = checked.result();
                var refs = checked.winningIds().stream().map(productId ->
                    new PositiveReference(productId, current.products().get(productId).currentProduct().name())).toList();
                feedbackEvidence = new FeedbackEvidence(checked.rankingMode(), checked.excludedNotInterestedCount(), refs);
            }
            return new ContentRecommendationResponse(Source.PROVIDER, catalog.scope(), result,
                result.items().stream().map(item -> current.products().get(item.productId()).currentProduct()).toList(),
                usedEvidence(before.activity(), result), feedbackEvidence);
        } catch (IllegalArgumentException invalid) {
            // No unfiltered retry or fabricated fallback after negative feedback.
            return unavailable(catalog.scope());
        }
    }

    private OwnedContentSnapshotService.Snapshot snapshot(String id, String epoch, List<Integer> ids,
            List<Integer> activityIds, boolean feedback,String namespace) {
        if(namespace!=null)return snapshots.read(id,epoch,ids,activityIds,feedback,namespace);
        return feedback ? snapshots.read(id, epoch, ids, activityIds, true) : snapshots.read(id, epoch, ids, activityIds);
    }
    private ContentRecommendationResponse unavailable(CatalogScope scope){
        return scope==CatalogScope.REVIEWED_LOCAL_CATALOG?ContentRecommendationResponse.unavailable(scope):ContentRecommendationResponse.unavailable();
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
            if (!text(item.explanation().path("comparison").get("kind"), "profile")) continue;
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
        if (!OwnedContentSnapshotService.matches(catalog, current.products())) changed();
    }
    private void changed(){throw new ResponseStatusException(HttpStatus.CONFLICT,"Recommendation inputs changed; request a current reviewed catalog again");}
}
