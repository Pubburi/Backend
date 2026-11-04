package com.pubburi.pub.recommendation;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pubburi.pub.controller.api.ProductSearchFilters;
import com.pubburi.pub.controller.response.SearchPhraseResponse.*;
import com.pubburi.pub.controller.response.UserPreferencesResponse;

/** Consumer guard, never a phrase generator or a local recommendation fallback. */
public final class SearchPhraseContract {
    private static final List<String> FIELDS = List.of("categories", "tasteTags", "aromaTags", "targetAbvPercent");
    private static final Set<String> ROOT = Set.of("algorithm", "items", "candidate_count", "omitted_count", "product_count", "diagnostics", "fallback_reason");
    private static final Set<String> ITEM = Set.of("template_id", "label", "conditions", "query_pairs", "backend_unicode_revalidation_required");
    private static final List<String> CONDITIONS = List.of("type", "minPriceKrw", "maxPriceKrw", "minAbvPercent", "maxAbvPercent", "tasteTag", "aromaTag");
    private final ObjectMapper mapper;
    public SearchPhraseContract(ObjectMapper mapper) { this.mapper = mapper; }

    public Result validate(JsonNode root, UserPreferencesResponse preferences, List<String> selected, int limit) {
        require(fields(root, ROOT) && text(root.get("algorithm"), "r06-explicit-search-phrases-v1")
                && root.path("product_count").isNull() && root.path("items").isArray()
                && root.path("diagnostics").isArray());
        var soft = preferences.softPreferences(); var hard = preferences.hardConstraints();
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("categories", soft.categories()); source.put("tasteTags", soft.tasteTags());
        source.put("aromaTags", soft.aromaTags()); source.put("targetAbvPercent", soft.targetAbvPercent());
        var diagnostics = new ArrayList<Diagnostic>(); boolean unknown = false, empty = false;
        for (String field : FIELDS) {
            Object value = source.get(field); String code = null;
            if (!selected.contains(field) && value != null) code = "soft_not_selected";
            else if (selected.contains(field) && value == null) { unknown = true; code = "selected_unknown"; }
            else if (selected.contains(field) && value instanceof List<?> list && list.isEmpty()) { empty = true; code = "selected_empty"; }
            if (code != null) diagnostics.add(new Diagnostic(field, code));
        }
        boolean conflict = !unknown && !empty && selected.contains("targetAbvPercent")
                && (hard.minAbvPercent() != null && soft.targetAbvPercent().compareTo(hard.minAbvPercent()) < 0
                    || hard.maxAbvPercent() != null && soft.targetAbvPercent().compareTo(hard.maxAbvPercent()) > 0);
        List<String> categories = selected.contains("categories") && !unknown && !empty ? soft.categories().stream().sorted().toList() : List.of();
        int count = unknown || empty || conflict ? 0 : selected.contains("categories") ? categories.size() : 1;
        require(integer(root.get("candidate_count"), count) && integer(root.get("omitted_count"), Math.max(0, count - limit))
                && root.path("items").size() == Math.min(count, limit));
        boolean hasHard = hard.minPriceKrw() != null || hard.maxPriceKrw() != null || hard.minAbvPercent() != null || hard.maxAbvPercent() != null;
        String fallback = unknown ? "selected_preferences_unknown" : empty ? "selected_preferences_empty"
                : conflict ? "selected_target_outside_hard_bounds" : !selected.isEmpty() ? null
                : hasHard || source.values().stream().anyMatch(Objects::nonNull) ? "no_search_opt_in" : "no_explicit_search_conditions";
        require(nullableText(root.get("fallback_reason"), fallback));
        boolean unicode = false;
        if (count != 0) for (String field : List.of("tasteTags", "aromaTags")) {
            if (selected.contains(field) && ((List<?>) source.get(field)).stream().map(String.class::cast)
                    .anyMatch(atom -> atom.chars().anyMatch(point -> point > 127))) {
                unicode = true; diagnostics.add(new Diagnostic(field, "backend_unicode_revalidation_required"));
            }
        }
        require(root.path("diagnostics").equals(mapper.valueToTree(diagnostics)));
        for (int index = 0; index < root.path("items").size(); index++) {
            JsonNode item = root.path("items").get(index); String category = categories.isEmpty() ? null : categories.get(index);
            require(fields(item, ITEM) && text(item.get("template_id"), !selected.isEmpty() ? "selected_preferences" : hasHard ? "hard_conditions" : "all_products")
                    && text(item.get("label"), category != null ? category + " 조건으로 검색"
                        : !selected.isEmpty() ? "선택한 취향 조건으로 검색" : hasHard ? "지정 조건으로 상품 보기" : "전체 상품 보기")
                    && item.path("backend_unicode_revalidation_required").isBoolean()
                    && item.path("backend_unicode_revalidation_required").booleanValue() == unicode);
            var conditions = item.get("conditions"); require(fields(conditions, Set.copyOf(CONDITIONS)));
            String minAbv = decimal(selected.contains("targetAbvPercent") ? soft.targetAbvPercent() : hard.minAbvPercent());
            String maxAbv = decimal(selected.contains("targetAbvPercent") ? soft.targetAbvPercent() : hard.maxAbvPercent());
            List<String> taste = selected.contains("tasteTags") ? soft.tasteTags().stream().sorted().toList() : List.of();
            List<String> aroma = selected.contains("aromaTags") ? soft.aromaTags().stream().sorted().toList() : List.of();
            require(nullableText(conditions.get("type"), category) && nullableInteger(conditions.get("minPriceKrw"), hard.minPriceKrw())
                    && nullableInteger(conditions.get("maxPriceKrw"), hard.maxPriceKrw())
                    && nullableText(conditions.get("minAbvPercent"), minAbv) && nullableText(conditions.get("maxAbvPercent"), maxAbv)
                    && conditions.path("tasteTag").equals(mapper.valueToTree(taste)) && conditions.path("aromaTag").equals(mapper.valueToTree(aroma)));
            var pairs = new ArrayList<QueryPair>();
            for (String name : CONDITIONS) {
                JsonNode value = conditions.get(name);
                if (value.isArray()) value.forEach(atom -> pairs.add(new QueryPair(name, atom.textValue())));
                else if (!value.isNull()) pairs.add(new QueryPair(name, value.asText()));
            }
            require(item.path("query_pairs").equals(mapper.valueToTree(pairs)));
        }
        // Only an entirely verified wire can be classified as an unusable saved condition.
        // A tampered pair/item remains an untrusted provider response instead of a client error.
        if (!root.path("items").isEmpty()) {
            String minAbv = decimal(selected.contains("targetAbvPercent") ? soft.targetAbvPercent() : hard.minAbvPercent());
            String maxAbv = decimal(selected.contains("targetAbvPercent") ? soft.targetAbvPercent() : hard.maxAbvPercent());
            try {
                ProductSearchFilters.fromQuery(number(hard.minPriceKrw()), number(hard.maxPriceKrw()), minAbv, maxAbv,
                        selected.contains("tasteTags") ? soft.tasteTags().toArray(String[]::new) : new String[0],
                        selected.contains("aromaTags") ? soft.aromaTags().toArray(String[]::new) : new String[0]);
            } catch (IllegalArgumentException unsupported) { throw new UnsupportedConditionsException(); }
        }
        try { return mapper.treeToValue(root, Result.class); }
        catch (com.fasterxml.jackson.core.JsonProcessingException invalid) { throw new IllegalArgumentException("Invalid search phrase response"); }
    }
    private static String decimal(BigDecimal value) { return value == null ? null : value.toPlainString(); }
    private static String number(Integer value) { return value == null ? null : value.toString(); }
    private static boolean text(JsonNode node, String value) { return node != null && node.isTextual() && value.equals(node.textValue()); }
    private static boolean nullableText(JsonNode node, String value) { return node != null && (value == null ? node.isNull() : text(node, value)); }
    private static boolean integer(JsonNode node, int value) { return node != null && node.isIntegralNumber() && node.canConvertToInt() && node.intValue() == value; }
    private static boolean nullableInteger(JsonNode node, Integer value) { return node != null && (value == null ? node.isNull() : integer(node, value)); }
    private static boolean fields(JsonNode node, Set<String> expected) {
        return node != null && node.isObject() && node.size() == expected.size() && expected.stream().allMatch(node::has);
    }
    private static void require(boolean valid) { if (!valid) throw new IllegalArgumentException("Invalid search phrase response"); }
    public static final class UnsupportedConditionsException extends IllegalArgumentException {
        public UnsupportedConditionsException() { super("Stored preferences cannot be used as search conditions"); }
    }
}
