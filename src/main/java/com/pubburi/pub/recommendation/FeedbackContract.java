package com.pubburi.pub.recommendation;

import static com.pubburi.pub.recommendation.ContentWire.*;

import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pubburi.pub.controller.response.ContentRecommendationResponse.Result;
import com.pubburi.pub.controller.response.UserPreferencesResponse;

/** Verifies feedback-specific reference and exclusion claims before sharing the ordinary B1 fact checks. */
public final class FeedbackContract {
    private static final List<String> METADATA = List.of("feedback_algorithm", "feedback_group_policy", "ranking_mode",
        "positive_references", "not_interested_references", "evaluated_positive_count", "informative_positive_count",
        "excluded_not_interested_count", "excluded_positive_group_count", "matches");
    private final ContentContract content;

    public FeedbackContract(ContentContract content) {
        this.content = content;
    }

    public record Checked(Result result, String rankingMode, int excludedNotInterestedCount, List<Integer> winningIds) { }

    public Checked validate(JsonNode root, JsonNode catalog, UserPreferencesResponse preferences, int limit, FeedbackSignals signals) {
        require(root instanceof ObjectNode && METADATA.stream().allMatch(root::has)
            && text(root.get("feedback_algorithm"), "r05-max-explicit-feedback-v1")
            && text(root.get("feedback_group_policy"), "exclude_all_known_feedback_groups"));

        var byId = new HashMap<Integer, JsonNode>();
        for (var row : catalog) {
            int id = integer(row.get("product_id"), 1, Integer.MAX_VALUE);
            require(byId.put(id, row) == null);
        }
        var negativeGroups = groups(signals.notInterested(), byId);
        var positiveGroups = groups(signals.matched(), byId);
        var excluded = new HashSet<Integer>();
        int negativeCount = 0;
        for (var entry : byId.entrySet()) {
            var group = entry.getValue().path("sku_group").textValue();
            if (negativeGroups.contains(group)) {
                negativeCount++;
                excluded.add(entry.getKey());
            } else if (positiveGroups.contains(group)) {
                excluded.add(entry.getKey());
            }
        }
        require(integer(root.get("excluded_not_interested_count"), 0, 1000) == negativeCount
            && integer(root.get("excluded_positive_group_count"), 0, 1000) == excluded.size() - negativeCount);

        var negatives = root.path("not_interested_references");
        require(negatives.isArray() && negatives.size() == signals.notInterested().size());
        for (int i = 0; i < negatives.size(); i++) {
            var actual = negatives.get(i);
            int id = signals.notInterested().get(i);
            require(fields(actual, "product_id", "sku_group", "state"));
            validateReference(actual, id, byId.get(id), false);
        }

        int eligible = (int) byId.entrySet().stream().filter(entry -> !excluded.contains(entry.getKey())
            && ContentContract.eligible(entry.getValue(), preferences)).count();
        var positives = root.path("positive_references");
        require(positives.isArray() && positives.size() == signals.matched().size());
        var spans = ContentContract.numericSpans(catalog);
        var usable = new HashSet<Integer>();
        var matchingReferences = new HashSet<Integer>();
        int informative = 0;
        for (int i = 0; i < positives.size(); i++) {
            var actual = positives.get(i);
            int id = signals.matched().get(i);
            var reference = byId.get(id);
            require(fields(actual, "product_id", "sku_group", "state", "supported_profile_tokens",
                "unknown_profile_tokens", "active_numeric_preferences", "fallback_reason"));
            if (!validateReference(actual, id, reference, negativeGroups.contains(reference.path("sku_group").textValue()))) {
                require(actual.path("supported_profile_tokens").isNull() && actual.path("unknown_profile_tokens").isNull()
                    && actual.path("active_numeric_preferences").isNull() && actual.path("fallback_reason").isNull());
                continue;
            }
            usable.add(id);
            var targets = ContentContract.referenceTargets(reference, spans);
            var tokens = tokens(reference);
            require(integer(actual.get("supported_profile_tokens"), 0, 41) == tokens.size()
                && integer(actual.get("unknown_profile_tokens"), 0, 41) == 0
                && textList(actual.get("active_numeric_preferences"), List.copyOf(targets.keySet())));
            boolean supported = !tokens.isEmpty() || !targets.isEmpty();
            if (supported) informative++;
            var fallback = actual.get("fallback_reason");
            if (eligible == 0) require(text(fallback, "no_eligible_products"));
            else if (!supported) require(text(fallback, "no_supported_preferences"));
            else if (fallback.isNull()) matchingReferences.add(id);
            else require(text(fallback, "no_matching_features"));
        }
        String rankingMode = informative > 0 ? "positive_feedback" : "profile_fallback";
        require(integer(root.get("evaluated_positive_count"), 0, 20) == usable.size()
            && integer(root.get("informative_positive_count"), 0, 20) == informative
            && text(root.get("ranking_mode"), rankingMode));

        var matches = root.path("matches");
        var items = root.path("items");
        require(matches.isArray() && items.isArray() && matches.size() == items.size() && items.size() <= limit
            && (!items.isEmpty() || matchingReferences.isEmpty()));
        var references = new HashMap<Integer, JsonNode>();
        var winningIds = new TreeSet<Integer>();
        for (int i = 0; i < items.size(); i++) {
            var match = matches.get(i);
            var item = items.get(i);
            int id = integer(item.get("product_id"), 1, Integer.MAX_VALUE);
            require(fields(match, "product_id", "source", "positive_product_id")
                && integer(match.get("product_id"), 1, Integer.MAX_VALUE) == id && item.path("score").isNumber());
            if (text(match.get("source"), "positive_feedback")) {
                int referenceId = integer(match.get("positive_product_id"), 1, Integer.MAX_VALUE);
                require(informative > 0 && matchingReferences.contains(referenceId));
                require(references.put(id, byId.get(referenceId)) == null);
                winningIds.add(referenceId);
            } else {
                require(match.path("positive_product_id").isNull() && text(match.get("source"),
                    item.path("score").decimalValue().signum() > 0 ? "profile" : "baseline"));
            }
        }

        var base = ((ObjectNode) root).deepCopy();
        base.remove(METADATA);
        var result = content.validate(base, catalog, preferences, limit, excluded, references, informative > 0);
        return new Checked(result, rankingMode, negativeCount, List.copyOf(winningIds));
    }

    private static Set<String> groups(List<Integer> ids, Map<Integer, JsonNode> catalog) {
        var groups = new HashSet<String>();
        int previous = 0;
        for (int id : ids) {
            require(id > previous && catalog.containsKey(id));
            groups.add(catalog.get(id).path("sku_group").textValue());
            previous = id;
        }
        return groups;
    }

    private static boolean validateReference(JsonNode actual, int id, JsonNode reference, boolean blocked) {
        boolean available = reference.path("available").booleanValue() && !blocked;
        require(integer(actual.get("product_id"), 1, Integer.MAX_VALUE) == id
            && equal(actual.get("sku_group"), reference.get("sku_group"))
            && text(actual.get("state"), blocked ? "blocked" : available ? "available" : "unavailable"));
        return available;
    }

    private static boolean textList(JsonNode array, List<String> expected) {
        if (!array.isArray() || array.size() != expected.size()) return false;
        for (int i = 0; i < expected.size(); i++) {
            if (!text(array.get(i), expected.get(i))) return false;
        }
        return true;
    }
}
