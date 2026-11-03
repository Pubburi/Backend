package com.pubburi.pub.controller.response;

import java.util.List;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(requiredProperties = {"source", "result"})
public record SearchPhraseResponse(Source source, @Schema(types = {"object", "null"}) Result result) {
    public enum Source { PROVIDER, UNAVAILABLE }
    @Schema(requiredProperties = {"algorithm", "items", "candidate_count", "omitted_count", "product_count", "diagnostics", "fallback_reason"})
    public record Result(String algorithm, List<Item> items,
            @JsonProperty("candidate_count") int candidateCount,
            @JsonProperty("omitted_count") int omittedCount,
            @JsonProperty("product_count") @Schema(types = {"null"}) Object productCount,
            List<Diagnostic> diagnostics,
            @JsonProperty("fallback_reason") @Schema(types = {"string", "null"}) String fallbackReason) { }
    @Schema(requiredProperties = {"template_id", "label", "conditions", "query_pairs", "backend_unicode_revalidation_required"})
    public record Item(@JsonProperty("template_id") String templateId, String label, Conditions conditions,
            @JsonProperty("query_pairs") List<QueryPair> queryPairs,
            @JsonProperty("backend_unicode_revalidation_required") boolean backendUnicodeRevalidationRequired) { }
    @Schema(requiredProperties = {"type", "minPriceKrw", "maxPriceKrw", "minAbvPercent", "maxAbvPercent", "tasteTag", "aromaTag"})
    public record Conditions(@Schema(types = {"string", "null"}) String type,
            @Schema(types = {"integer", "null"}) Integer minPriceKrw,
            @Schema(types = {"integer", "null"}) Integer maxPriceKrw,
            @Schema(types = {"string", "null"}) String minAbvPercent,
            @Schema(types = {"string", "null"}) String maxAbvPercent,
            List<String> tasteTag, List<String> aromaTag) { }
    public record QueryPair(String name, String value) { }
    public record Diagnostic(String field, String code) { }
}
