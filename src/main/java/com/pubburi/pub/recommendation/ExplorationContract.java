package com.pubburi.pub.recommendation;

import static com.pubburi.pub.recommendation.ContentWire.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pubburi.pub.controller.response.ExplorationRecommendationResponse.Result;
import com.pubburi.pub.controller.response.ExplorationRecommendationResponse.Selection;
import com.pubburi.pub.controller.response.UserPreferencesResponse;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Checks R08's reranking envelope while the shared B1 validator checks facts and scores. */
public final class ExplorationContract {
    private static final List<String> EXPLORATION_FIELDS = List.of("reranking_algorithm", "policy", "candidate_pool_limit",
        "candidate_pool_size", "pool_truncated", "relevance_pool_size", "selected_count", "selected_group_count",
        "exploration_reason", "selections");
    private static final double RELEVANCE_WEIGHT = 0.7;
    private final ObjectMapper mapper;
    private final ContentContract content;

    public ExplorationContract(ObjectMapper mapper, ContentContract content) {
        this.mapper = mapper;
        this.content = content;
    }

    public Result validate(JsonNode root, JsonNode catalog, UserPreferencesResponse preferences, int limit,
            Set<Integer> excluded) {
        require(root.isObject() && EXPLORATION_FIELDS.stream().allMatch(root::has));
        require(text(root.get("reranking_algorithm"), "r08-text-mmr-group-v1"));
        var policy = root.get("policy");
        require(fields(policy, "relevance_weight", "min_relevance_score")
            && equal(policy.get("relevance_weight"), mapper.valueToTree(RELEVANCE_WEIGHT))
            && equal(policy.get("min_relevance_score"), mapper.valueToTree(0)));

        ObjectNode base = root.deepCopy();
        base.remove(EXPLORATION_FIELDS);
        var checked = content.validateExploration(base, catalog, preferences, limit, excluded);
        int pool = Math.min(100, checked.eligibleCount());
        var truncated = root.get("pool_truncated");
        integer(root.get("candidate_pool_limit"), 100, 100);
        require(integer(root.get("candidate_pool_size"), 0, 100) == pool
            && truncated.isBoolean() && truncated.booleanValue() == (checked.eligibleCount() > pool));

        int count = checked.items().size();
        int relevance = integer(root.get("relevance_pool_size"), count, pool);
        var selections = root.get("selections");
        require(integer(root.get("selected_count"), 0, limit) == count
            && integer(root.get("selected_group_count"), 0, limit) == count
            && selections.isArray() && selections.size() == count);
        // At the fixed zero floor, an honest empty result has B1 fallback and no positive candidates.
        String reason = count == 0 ? "baseline_fallback" : "selected";
        require(text(root.get("exploration_reason"), reason) && (count == 0) == (relevance == 0));

        var byId = new HashMap<Integer, JsonNode>();
        for (var row : catalog) byId.put(row.path("product_id").intValue(), row);
        var seenGroups = new HashSet<String>();
        var publicSelections = new ArrayList<Selection>();
        boolean allPreviousTextKnown = true;
        for (int index = 0; index < count; index++) {
            var selection = selections.get(index);
            var item = checked.items().get(index);
            var product = byId.get(item.productId());
            String group = product.path("sku_group").textValue();
            boolean textKnown = !tokens(product).isEmpty();
            require(fields(selection, "product_id", "sku_group", "selection_score", "redundancy_penalty_input",
                "max_observed_text_similarity", "basis")
                && integer(selection.get("product_id"), 1, Integer.MAX_VALUE) == item.productId()
                && group != null && text(selection.get("sku_group"), group) && seenGroups.add(group));
            double penalty = number(selection.get("redundancy_penalty_input"), 0, 1);
            var observed = selection.get("max_observed_text_similarity");
            var basis = selection.get("basis");
            if (index == 0) {
                require(text(basis, "highest_relevance_anchor") && penalty == 0 && observed.isNull());
            } else {
                var anchor = checked.items().get(0);
                int compared = anchor.score().compareTo(item.score());
                require(compared > 0 || compared == 0 && anchor.productId() < item.productId());
                if (textKnown && allPreviousTextKnown) {
                    require(text(basis, "observed_text") && number(observed, 0, 1) == penalty);
                } else {
                    require(text(basis, "unknown_conservative") && penalty == 1 && observed.isNull());
                }
            }
            allPreviousTextKnown &= textKnown;
            double score = number(selection.get("selection_score"), -1, 1);
            require(Math.abs(score - (RELEVANCE_WEIGHT * item.score().doubleValue() - (1 - RELEVANCE_WEIGHT) * penalty)) <= 1e-9);
            publicSelections.add(new Selection(item.productId(), basis.textValue()));
        }
        return Result.from(checked, reason, publicSelections);
    }

    private static double number(JsonNode value, double min, double max) {
        require(value.isNumber());
        double number = value.doubleValue();
        require(Double.isFinite(number) && number >= min && number <= max);
        return number;
    }
}
