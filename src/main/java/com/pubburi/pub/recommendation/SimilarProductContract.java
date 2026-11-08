package com.pubburi.pub.recommendation;

import static com.pubburi.pub.recommendation.ContentWire.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pubburi.pub.controller.response.SimilarProductsResponse.Explanation;
import com.pubburi.pub.controller.response.UserPreferencesResponse;

/** Checks the provider's public link candidates without rebuilding model scores. */
public final class SimilarProductContract {
    private static final List<String> NUMERICS = List.of("abv_percent", "volume_ml");

    private SimilarProductContract() { }

    public static boolean hasAvailableReference(JsonNode catalog, int referenceId) {
        var reference = findReference(catalog, referenceId);
        return reference != null && reference.path("available").booleanValue();
    }

    public static List<Integer> validate(JsonNode root, JsonNode catalog, int referenceId, int limit) {
        return validate(root,catalog,referenceId,limit,false);
    }

    public static List<Explanation> validateExplained(JsonNode root,JsonNode catalog,int referenceId,int limit,ObjectMapper mapper){
        var ids=validate(root,catalog,referenceId,limit,true);
        var reference=findReference(catalog,referenceId);var excluded=new java.util.HashSet<Integer>();
        for(var row:catalog)if(text(row.get("sku_group"),reference.path("sku_group").textValue()))excluded.add(row.path("product_id").intValue());
        var references=new HashMap<Integer,JsonNode>();for(int id:ids)references.put(id,reference);
        // Share the existing typed fact/top-three/units boundary without inferring a user profile or score.
        var base=mapper.createObjectNode();
        for(String field:List.of("algorithm","fit_scope","representation_fitted","model_fingerprint","items","eligible_count"))base.set(field,root.get(field));
        base.put("supported_profile_tokens",0).put("unknown_profile_tokens",0).putArray("active_numeric_preferences");
        if(root.path("eligible_count").intValue()==0)base.put("fallback_reason","no_eligible_products");
        else if(ids.isEmpty())base.put("fallback_reason","no_matching_features");else base.putNull("fallback_reason");
        var preferences=new UserPreferencesResponse("NOT_STARTED",new UserPreferencesResponse.PreferenceSoftResponse(null,null,null,null),
            new UserPreferencesResponse.PreferenceHardResponse(null,null,null,null));
        return new ContentContract(mapper).validate(base,catalog,preferences,limit,excluded,references,true).items().stream()
            .map(item->new Explanation(item.productId(),item.explanation())).toList();
    }

    private static List<Integer> validate(JsonNode root,JsonNode catalog,int referenceId,int limit,boolean explained){
        require(fields(root, "algorithm", "fit_scope", "representation_fitted", "model_fingerprint", "items",
            "eligible_count", "supported_profile_tokens", "unknown_profile_tokens", "active_numeric_preferences",
            "fallback_reason", "reference_product_id", "reference_sku_group", "reference_state", "profile_source",
            "reference_group_policy", "excluded_reference_group_count"));
        var items = root.get("items");
        require(items.isArray() && items.size() <= limit);
        if (items.isEmpty() && !explained) return List.of();

        var reference = findReference(catalog, referenceId);
        require(reference != null && reference.path("available").booleanValue());
        String group = reference.path("sku_group").textValue();
        var eligible = new HashMap<Integer, JsonNode>();
        int excluded = 0;
        for (var row : catalog) {
            if (text(row.get("sku_group"), group)) excluded++;
            else if (row.path("available").booleanValue()) eligible.put(row.path("product_id").intValue(), row);
        }
        var referenceTokens = tokens(reference);
        var spans = numericSpans(catalog, reference);
        boolean fitted=!ContentContract.numericSpans(catalog).isEmpty();
        for(var row:catalog)fitted|=!tokens(row).isEmpty();
        String fallback=items.isEmpty()?eligible.isEmpty()?"no_eligible_products":referenceTokens.isEmpty() && spans.isEmpty()?"no_supported_preferences":"no_matching_features":null;
        require(text(root.get("algorithm"), "b1-tfidf-minmax-v1") && text(root.get("fit_scope"), "request_catalog")
            && root.path("representation_fitted").isBoolean() && root.path("representation_fitted").booleanValue()==fitted
            && (fitted?root.path("model_fingerprint").isTextual() && root.path("model_fingerprint").textValue().matches("[0-9a-f]{64}"):root.path("model_fingerprint").isNull())
            && integer(root.get("reference_product_id"), 1, Integer.MAX_VALUE) == referenceId
            && text(root.get("reference_sku_group"), group) && text(root.get("reference_state"), "available")
            && text(root.get("profile_source"), "reference_product_facts")
            && text(root.get("reference_group_policy"), "exclude_reference_group")
            && integer(root.get("excluded_reference_group_count"), 1, 1000) == excluded
            && integer(root.get("eligible_count"), 0, 1000) == eligible.size()
            && integer(root.get("supported_profile_tokens"), 0, 41) == referenceTokens.size()
            && integer(root.get("unknown_profile_tokens"), 0, 41) == 0
            && textList(root.get("active_numeric_preferences"), List.copyOf(spans.keySet()))
            && equal(root.get("fallback_reason"),fallback==null?com.fasterxml.jackson.databind.node.NullNode.instance:com.fasterxml.jackson.databind.node.TextNode.valueOf(fallback)));

        var result = new ArrayList<Integer>();
        BigDecimal previous = BigDecimal.valueOf(2);
        int previousId = 0;
        for (var item : items) {
            require(explained?fields(item,"product_id","score","reasons","contributions","explanation")
                :fields(item, "product_id", "score", "reasons", "contributions"));
            int id = integer(item.get("product_id"), 1, Integer.MAX_VALUE);
            // Removing the ID also rejects duplicates and never admits the reference group.
            var candidate = eligible.remove(id);
            require(candidate != null);
            BigDecimal score = positiveUnit(item.get("score"));
            int order = score.compareTo(previous);
            require(order < 0 || order == 0 && id > previousId);
            require(textList(item.get("reasons"), List.of("available", "content_similarity")));
            evidence(item.get("contributions"), reference, candidate, referenceTokens, spans, score);
            result.add(id);
            previous = score;
            previousId = id;
        }
        return List.copyOf(result);
    }

    private static JsonNode findReference(JsonNode catalog, int referenceId) {
        for (var row : catalog) if (row.path("product_id").intValue() == referenceId) return row;
        return null;
    }

    private static Map<String, Double> numericSpans(JsonNode catalog, JsonNode reference) {
        var spans = new LinkedHashMap<String, Double>();
        for (String field : NUMERICS) {
            if (reference.path(field).isNull()) continue;
            double minimum = Double.POSITIVE_INFINITY;
            double maximum = Double.NEGATIVE_INFINITY;
            for (var row : catalog) {
                var value = row.path(field);
                if (!value.isNumber()) continue;
                minimum = Math.min(minimum, value.doubleValue());
                maximum = Math.max(maximum, value.doubleValue());
            }
            if (maximum > minimum) spans.put(field, maximum - minimum);
        }
        return spans;
    }

    private static void evidence(JsonNode parts, JsonNode reference, JsonNode candidate, Set<String> referenceTokens,
            Map<String, Double> spans, BigDecimal score) {
        require(parts.isArray() && !parts.isEmpty() && parts.size() <= 43);
        var candidateTokens = tokens(candidate);
        String previous = "";
        BigDecimal sum = BigDecimal.ZERO;
        for (var part : parts) {
            require(fields(part, "component", "feature", "contribution")
                && part.path("component").isTextual() && part.path("feature").isTextual());
            String component = part.path("component").textValue();
            String feature = part.path("feature").textValue();
            String key = component + "\0" + feature;
            require(compareCodePoints(key,previous) > 0);
            previous = key;
            if (component.equals("text")) {
                require(referenceTokens.contains(feature) && candidateTokens.contains(feature));
            } else {
                var span = spans.get(component);
                var actual = candidate.path(component);
                require(component.equals(feature) && span != null && actual.isNumber()
                    && Math.abs(actual.doubleValue() - reference.path(component).doubleValue()) < span);
            }
            sum = sum.add(positiveUnit(part.get("contribution")));
        }
        require(sum.min(BigDecimal.ONE).subtract(score).abs().compareTo(new BigDecimal("0.000000001")) <= 0);
    }

    private static BigDecimal positiveUnit(JsonNode value) {
        require(value != null && value.isNumber() && Double.isFinite(value.doubleValue()));
        var number = value.decimalValue();
        require(number.signum() > 0 && number.compareTo(BigDecimal.ONE) <= 0);
        return number;
    }

    private static boolean textList(JsonNode array, List<String> expected) {
        if (array == null || !array.isArray() || array.size() != expected.size()) return false;
        for (int index = 0; index < expected.size(); index++) {
            if (!text(array.get(index), expected.get(index))) return false;
        }
        return true;
    }
}
