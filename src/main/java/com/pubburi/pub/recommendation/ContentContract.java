package com.pubburi.pub.recommendation;

import static com.pubburi.pub.recommendation.ContentWire.*;

import java.math.BigDecimal;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pubburi.pub.controller.response.ContentRecommendationResponse.Result;
import com.pubburi.pub.controller.response.UserPreferencesResponse;

/** Checks candidate/fact/constraint boundaries without regenerating model scores or rankings. */
public final class ContentContract {
    private static final List<String> NUMERICS = List.of("abv_percent", "volume_ml");
    private final ObjectMapper mapper;

    public ContentContract(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public Result validate(JsonNode root, JsonNode catalog, UserPreferencesResponse preferences, int limit) {
        return validate(root, catalog, preferences, limit, Set.of(), Map.of(), ResultMode.CONTENT);
    }

    Result validate(JsonNode root, JsonNode catalog, UserPreferencesResponse preferences, int limit,
            Set<Integer> excluded, Map<Integer, JsonNode> references, boolean positiveOnly) {
        return validate(root, catalog, preferences, limit, excluded, references,
            positiveOnly ? ResultMode.POSITIVE_RANKED : ResultMode.CONTENT);
    }

    Result validateExploration(JsonNode root, JsonNode catalog, UserPreferencesResponse preferences, int limit,
            Set<Integer> excluded) {
        return validate(root, catalog, preferences, limit, excluded, Map.of(), ResultMode.EXPLORATION);
    }

    private enum ResultMode { CONTENT, POSITIVE_RANKED, EXPLORATION }

    private Result validate(JsonNode root, JsonNode catalog, UserPreferencesResponse preferences, int limit,
            Set<Integer> excluded, Map<Integer, JsonNode> references, ResultMode mode) {
        boolean positiveOnly = mode != ResultMode.CONTENT;
        require(fields(root, "algorithm", "fit_scope", "representation_fitted", "model_fingerprint", "items",
            "eligible_count", "supported_profile_tokens", "unknown_profile_tokens", "active_numeric_preferences", "fallback_reason"));
        require(text(root.get("algorithm"), "b1-tfidf-minmax-v1") && text(root.get("fit_scope"), "request_catalog"));
        var eligible = new HashMap<Integer, JsonNode>();
        var vocabulary = new HashSet<String>();
        var ids = new HashSet<Integer>();
        var spans = numericSpans(catalog);
        for (var product : catalog) {
            int id = integer(product.get("product_id"), 1, Integer.MAX_VALUE);
            require(ids.add(id));
            vocabulary.addAll(tokens(product));
            if (!excluded.contains(id) && eligible(product, preferences)) eligible.put(id, product);
        }

        var soft = preferences.softPreferences();
        var profileTokens = new HashSet<String>();
        add(profileTokens, "category", soft.categories());
        add(profileTokens, "taste", soft.tasteTags());
        add(profileTokens, "aroma", soft.aromaTags());
        int supported = (int) profileTokens.stream().filter(vocabulary::contains).count();
        var profileTargets = new LinkedHashMap<String, JsonNode>();
        if (soft.targetAbvPercent() != null && spans.containsKey("abv_percent")) {
            profileTargets.put("abv_percent", mapper.valueToTree(soft.targetAbvPercent()));
        }
        boolean supportedPreference = supported > 0 || !profileTargets.isEmpty();
        require(integer(root.get("eligible_count"), 0, 1000) == eligible.size()
            && integer(root.get("supported_profile_tokens"), 0, 72) == supported
            && integer(root.get("unknown_profile_tokens"), 0, 72) == profileTokens.size() - supported);
        require(root.path("active_numeric_preferences").equals(mapper.valueToTree(List.copyOf(profileTargets.keySet()))));
        boolean fitted = !vocabulary.isEmpty() || !spans.isEmpty();
        require(root.path("representation_fitted").isBoolean() && root.path("representation_fitted").booleanValue() == fitted);
        require(fitted ? root.path("model_fingerprint").isTextual() && root.path("model_fingerprint").textValue().matches("[a-f0-9]{64}")
            : root.path("model_fingerprint").isNull());

        var items = root.get("items");
        int maxItems = Math.min(limit, eligible.size());
        require(items.isArray() && (positiveOnly ? items.size() <= maxItems : items.size() == maxItems));
        boolean allZero = true;
        for (var item : items) {
            number(item.get("score"), positiveOnly);
            allZero &= item.path("score").decimalValue().signum() == 0;
        }
        String fallback = null;
        if (eligible.isEmpty()) fallback = "no_eligible_products";
        else if (mode != ResultMode.POSITIVE_RANKED && !supportedPreference) fallback = "no_supported_preferences";
        else if (allZero) fallback = "no_matching_features";
        require(equal(root.get("fallback_reason"), mapper.valueToTree(fallback)));

        var seen = new HashSet<Integer>();
        BigDecimal previous = BigDecimal.valueOf(2);
        int previousId = 0;
        for (var item : items) {
            require(fields(item, "product_id", "score", "reasons", "contributions", "explanation"));
            int id = integer(item.get("product_id"), 1, Integer.MAX_VALUE);
            require(eligible.containsKey(id) && seen.add(id));
            var product = eligible.get(id);
            var reference = references.get(id);
            BigDecimal score = item.path("score").decimalValue();
            if (mode != ResultMode.EXPLORATION) {
                require(score.compareTo(previous) < 0 || score.compareTo(previous) == 0 && id > previousId);
            }
            previous = score;
            previousId = id;

            var comparison = mapper.createObjectNode().put("kind", "profile");
            Set<String> selectedTokens = profileTokens;
            Map<String, JsonNode> targets = profileTargets;
            if (reference != null) {
                require(positiveOnly && score.signum() > 0);
                comparison.put("kind", "reference_product").set("product_id", reference.get("product_id"));
                selectedTokens = tokens(reference);
                targets = referenceTargets(reference, spans);
            } else if (positiveOnly) {
                require(supportedPreference);
            }

            var hard = preferences.hardConstraints();
            var reasons = new ArrayList<String>();
            reasons.add("available");
            if (hard.minPriceKrw() != null || hard.maxPriceKrw() != null) reasons.add("price_within_range");
            if (hard.minAbvPercent() != null || hard.maxAbvPercent() != null) reasons.add("abv_within_range");
            boolean supportedSource = reference != null || supportedPreference;
            reasons.add(!supportedSource ? "deterministic_baseline" : score.signum() > 0 ? "content_similarity" : "no_shared_features");
            if (supportedSource && allZero) reasons.add("deterministic_baseline");
            require(item.get("reasons").equals(mapper.valueToTree(reasons)));

            var contributions = item.get("contributions");
            require(contributions.isArray() && contributions.size() <= 43);
            double sum = 0;
            String last = "";
            var positive = new ArrayList<JsonNode>();
            var actualTokens = tokens(product);
            for (var part : contributions) {
                require(fields(part, "component", "feature", "contribution")
                    && part.path("component").isTextual() && part.path("feature").isTextual());
                number(part.get("contribution"), true);
                String key = part.path("component").textValue() + "\u0000" + part.path("feature").textValue();
                require(compareCodePoints(key,last) > 0);
                last = key;
                sum += part.path("contribution").doubleValue();
                positive.add(part);
            }
            require((score.signum() > 0) == !positive.isEmpty() && Double.isFinite(sum) && Math.abs(score.doubleValue() - sum) <= 1e-9);
            positive.sort(Comparator.<JsonNode, BigDecimal>comparing(part -> part.path("contribution").decimalValue())
                .reversed().thenComparing(part -> part.path("component").textValue(),ContentWire::compareCodePoints)
                .thenComparing(part -> part.path("feature").textValue(),ContentWire::compareCodePoints));
            var expectedEvidence = mapper.createArrayNode();
            for (var part : positive) {
                var fact = evidence(part, product, actualTokens, selectedTokens, targets, reference == null ? Map.of() : spans);
                if (expectedEvidence.size() < 3) expectedEvidence.add(fact);
            }
            String basis = score.signum() > 0 ? "content_similarity"
                : !supportedSource || allZero ? "deterministic_baseline" : "no_shared_features";
            explanation(item.get("explanation"), product, preferences, comparison, expectedEvidence, positive.size(), basis,
                root.get("fallback_reason"));
        }
        try {
            return mapper.treeToValue(root, Result.class);
        } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
            throw new IllegalArgumentException("Invalid content recommendation response");
        }
    }

    static Map<String, Double> numericSpans(JsonNode catalog) {
        var spans = new LinkedHashMap<String, Double>();
        for (String field : NUMERICS) {
            var values = new DoubleSummaryStatistics();
            for (var product : catalog) {
                if (product.path(field).isNumber()) values.accept(product.get(field).doubleValue());
            }
            if (values.getMax() > values.getMin()) spans.put(field, values.getMax() - values.getMin());
        }
        return spans;
    }

    static Map<String, JsonNode> referenceTargets(JsonNode reference, Map<String, Double> spans) {
        var targets = new LinkedHashMap<String, JsonNode>();
        for (String field : spans.keySet()) {
            if (reference.path(field).isNumber()) targets.put(field, reference.get(field));
        }
        return targets;
    }

    static boolean eligible(JsonNode product, UserPreferencesResponse preferences) {
        var hard = preferences.hardConstraints();
        return product.path("available").booleanValue()
            && range(product.get("price_krw"), price(hard.minPriceKrw()), price(hard.maxPriceKrw()))
            && range(product.get("abv_percent"), hard.minAbvPercent(), hard.maxAbvPercent());
    }

    private static BigDecimal price(Integer value) {
        return value == null ? null : BigDecimal.valueOf(value);
    }

    private static boolean range(JsonNode value, BigDecimal minimum, BigDecimal maximum) {
        return minimum == null && maximum == null || !value.isNull()
            && (minimum == null || value.decimalValue().compareTo(minimum) >= 0)
            && (maximum == null || value.decimalValue().compareTo(maximum) <= 0);
    }

    private static void add(Set<String> values, String kind, List<String> atoms) {
        if (atoms != null) atoms.forEach(atom -> values.add(kind + ":" + atom));
    }

    private static void number(JsonNode node, boolean positive) {
        require(node != null && node.isNumber() && Double.isFinite(node.doubleValue())
            && node.decimalValue().compareTo(BigDecimal.ZERO) >= (positive ? 1 : 0)
            && node.decimalValue().compareTo(BigDecimal.ONE) <= 0);
    }

    private ObjectNode evidence(JsonNode part, JsonNode product, Set<String> actualTokens, Set<String> selectedTokens,
            Map<String, JsonNode> targets, Map<String, Double> referenceSpans) {
        String component = part.path("component").textValue(), feature = part.path("feature").textValue();
        var node = mapper.createObjectNode();
        if (component.equals("text")) {
            require(actualTokens.contains(feature) && selectedTokens.contains(feature));
            int separator = feature.indexOf(':');
            String kind = feature.substring(0, separator), atom = feature.substring(separator + 1);
            String label = switch (kind) {
                case "category" -> "주종 일치";
                case "taste" -> "맛 태그 일치";
                case "aroma" -> "향 태그 일치";
                default -> throw new IllegalArgumentException("Invalid content feature");
            };
            node.put("kind", kind + "_match").put("label", label).put("actual_value", atom).put("target_value", atom);
        } else {
            var target = targets.get(component);
            var actual = product.path(component);
            require(component.equals(feature) && target != null && actual.isNumber());
            Double span = referenceSpans.get(component);
            if (span != null) require(Math.abs(actual.doubleValue() - target.doubleValue()) < span);
            boolean abv = component.equals("abv_percent");
            node.put("kind", abv ? "abv_comparison" : "volume_comparison");
            node.put("label", abv ? "도수 비교 근거" : "용량 비교 근거");
            node.set("actual_value", actual);
            node.set("target_value", target);
            node.put("unit", abv ? "vol%" : "mL");
        }
        node.set("contribution", part.get("contribution"));
        return node;
    }

    private void explanation(JsonNode value, JsonNode product, UserPreferencesResponse preferences, ObjectNode comparison,
            JsonNode expectedEvidence, int count, String basis, JsonNode fallback) {
        require(fields(value, "version", "basis", "comparison", "score_evidence", "total_positive_contributions",
            "score_evidence_truncated", "filter_evidence", "fallback_reason"));
        require(text(value.get("version"), "r04-facts-v1") && equal(value.get("comparison"), comparison)
            && text(value.get("basis"), basis) && equal(value.get("fallback_reason"), fallback));
        require(integer(value.get("total_positive_contributions"), 0, 43) == count
            && value.path("score_evidence_truncated").isBoolean() && value.path("score_evidence_truncated").booleanValue() == (count > 3)
            && equal(value.get("score_evidence"), expectedEvidence));
        var filters = mapper.createArrayNode();
        filters.addObject().put("kind", "availability_filter").put("label", "입력 가용 조건 충족").put("actual_value", true);
        var hard = preferences.hardConstraints();
        if (hard.minPriceKrw() != null || hard.maxPriceKrw() != null) {
            var node = filters.addObject().put("kind", "price_filter").put("label", "입력 가격 조건 충족");
            node.set("actual_value", product.get("price_krw"));
            node.set("minimum", mapper.valueToTree(hard.minPriceKrw()));
            node.set("maximum", mapper.valueToTree(hard.maxPriceKrw()));
            node.put("unit", "KRW");
        }
        if (hard.minAbvPercent() != null || hard.maxAbvPercent() != null) {
            var node = filters.addObject().put("kind", "abv_filter").put("label", "입력 도수 조건 충족");
            node.set("actual_value", product.get("abv_percent"));
            node.set("minimum", mapper.valueToTree(hard.minAbvPercent()));
            node.set("maximum", mapper.valueToTree(hard.maxAbvPercent()));
            node.put("unit", "vol%");
        }
        require(equal(value.get("filter_evidence"), filters));
        try {
            require(mapper.writeValueAsBytes(value).length <= 4096);
        } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
            throw new IllegalArgumentException("Invalid content explanation");
        }
    }
}
