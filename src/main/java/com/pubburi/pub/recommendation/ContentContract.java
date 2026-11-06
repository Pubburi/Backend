package com.pubburi.pub.recommendation;

import static com.pubburi.pub.recommendation.ContentWire.*;
import java.math.BigDecimal;
import java.util.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.pubburi.pub.controller.response.ContentRecommendationResponse.Result;
import com.pubburi.pub.controller.response.UserPreferencesResponse;

/** Checks candidate/fact/constraint boundaries; never regenerates model scores or rankings. */
public final class ContentContract {
    private final ObjectMapper mapper;
    public ContentContract(ObjectMapper mapper){this.mapper=mapper;}
    public Result validate(JsonNode root,JsonNode catalog,UserPreferencesResponse preferences,int limit){
        require(fields(root,"algorithm","fit_scope","representation_fitted","model_fingerprint","items","eligible_count","supported_profile_tokens","unknown_profile_tokens","active_numeric_preferences","fallback_reason"));
        require(text(root.get("algorithm"),"b1-tfidf-minmax-v1") && text(root.get("fit_scope"),"request_catalog"));
        var byId=new HashMap<Integer,JsonNode>();var vocabulary=new HashSet<String>();var eligible=new HashSet<Integer>();
        var abv=new TreeSet<BigDecimal>();var volume=new TreeSet<BigDecimal>();
        for(var product:catalog){int id=integer(product.get("product_id"),1,Integer.MAX_VALUE);require(byId.put(id,product)==null);vocabulary.addAll(tokens(product));
            if(!product.path("abv_percent").isNull())abv.add(product.path("abv_percent").decimalValue());
            if(!product.path("volume_ml").isNull())volume.add(product.path("volume_ml").decimalValue());
            if(product.path("available").booleanValue() && eligible(product,preferences))eligible.add(id);
        }
        var soft=preferences.softPreferences();var profile=new HashSet<String>();
        add(profile,"category",soft.categories());add(profile,"taste",soft.tasteTags());add(profile,"aroma",soft.aromaTags());
        int supported=(int)profile.stream().filter(vocabulary::contains).count();boolean numeric=soft.targetAbvPercent()!=null && abv.size()>1;
        require(integer(root.get("eligible_count"),0,1000)==eligible.size() && integer(root.get("supported_profile_tokens"),0,72)==supported && integer(root.get("unknown_profile_tokens"),0,72)==profile.size()-supported);
        require(root.path("active_numeric_preferences").equals(mapper.valueToTree(numeric?List.of("abv_percent"):List.of())));
        boolean fitted=!vocabulary.isEmpty() || abv.size()>1 || volume.size()>1;
        require(root.path("representation_fitted").isBoolean() && root.path("representation_fitted").booleanValue()==fitted);
        require(fitted ? root.path("model_fingerprint").isTextual() && root.path("model_fingerprint").textValue().matches("[a-f0-9]{64}") : root.path("model_fingerprint").isNull());
        var items=root.get("items");require(items.isArray() && items.size()==Math.min(limit,eligible.size()));
        boolean supportedPreference=supported>0 || numeric;
        boolean allZero=true;for(var item:items){number(item.get("score"),false);allZero &= item.path("score").decimalValue().signum()==0;}
        String fallback=eligible.isEmpty()?"no_eligible_products":!supportedPreference?"no_supported_preferences":allZero?"no_matching_features":null;
        require(equal(root.get("fallback_reason"),mapper.valueToTree(fallback)));
        var seen=new HashSet<Integer>();BigDecimal previous=BigDecimal.valueOf(2);int previousId=0;
        for(var item:items){
            require(fields(item,"product_id","score","reasons","contributions","explanation"));int id=integer(item.get("product_id"),1,Integer.MAX_VALUE);require(eligible.contains(id) && seen.add(id));
            JsonNode product=byId.get(id);BigDecimal score=item.path("score").decimalValue();require(score.compareTo(previous)<0 || score.compareTo(previous)==0 && id>previousId);previous=score;previousId=id;
            var reasons=new ArrayList<String>();reasons.add("available");var hard=preferences.hardConstraints();
            if(hard.minPriceKrw()!=null || hard.maxPriceKrw()!=null)reasons.add("price_within_range");
            if(hard.minAbvPercent()!=null || hard.maxAbvPercent()!=null)reasons.add("abv_within_range");
            reasons.add(!supportedPreference?"deterministic_baseline":score.signum()>0?"content_similarity":"no_shared_features");
            if(supportedPreference && allZero)reasons.add("deterministic_baseline");require(item.get("reasons").equals(mapper.valueToTree(reasons)));
            var contributions=item.get("contributions");require(contributions.isArray() && contributions.size()<=43);double sum=0;String last="";var positive=new ArrayList<JsonNode>();
            for(var part:contributions){
                require(fields(part,"component","feature","contribution") && part.path("component").isTextual() && part.path("feature").isTextual());number(part.get("contribution"),true);
                String key=part.path("component").textValue()+"\u0000"+part.path("feature").textValue();require(key.compareTo(last)>0);last=key;
                evidence(part,product,preferences,numeric);sum+=part.path("contribution").doubleValue();positive.add(part);
            }
            require((score.signum()>0)==!positive.isEmpty() && Double.isFinite(sum) && Math.abs(score.doubleValue()-sum)<=1e-9);
            positive.sort(Comparator.<JsonNode,BigDecimal>comparing(part->part.path("contribution").decimalValue()).reversed().thenComparing(part->part.path("component").textValue()).thenComparing(part->part.path("feature").textValue()));
            explanation(item.get("explanation"),product,preferences,numeric,positive,score,allZero,supportedPreference,root.get("fallback_reason"));
        }
        try{return mapper.treeToValue(root,Result.class);}catch(com.fasterxml.jackson.core.JsonProcessingException invalid){throw new IllegalArgumentException("Invalid content recommendation response");}
    }
    private boolean eligible(JsonNode product,UserPreferencesResponse preferences){var h=preferences.hardConstraints();return range(product.get("price_krw"),h.minPriceKrw()==null?null:BigDecimal.valueOf(h.minPriceKrw()),h.maxPriceKrw()==null?null:BigDecimal.valueOf(h.maxPriceKrw())) && range(product.get("abv_percent"),h.minAbvPercent(),h.maxAbvPercent());}
    private boolean range(JsonNode value,BigDecimal min,BigDecimal max){if(min==null && max==null)return true;return !value.isNull() && (min==null || value.decimalValue().compareTo(min)>=0) && (max==null || value.decimalValue().compareTo(max)<=0);}
    private void add(Set<String> values,String kind,List<String> atoms){if(atoms!=null)atoms.forEach(atom->values.add(kind+":"+atom));}
    private void number(JsonNode node,boolean positive){require(node!=null && node.isNumber() && Double.isFinite(node.doubleValue()) && node.decimalValue().compareTo(BigDecimal.ZERO)>=(positive?1:0) && node.decimalValue().compareTo(BigDecimal.ONE)<=0);}
    private ObjectNode evidence(JsonNode part,JsonNode product,UserPreferencesResponse preferences,boolean numeric){
        String component=part.path("component").textValue(),feature=part.path("feature").textValue();var node=mapper.createObjectNode();var soft=preferences.softPreferences();
        if(component.equals("text")){
            int separator=feature.indexOf(':');require(separator>0);String kind=feature.substring(0,separator),atom=feature.substring(separator+1);String label;
            List<String> selected=switch(kind){case "category"->soft.categories();case "taste"->soft.tasteTags();case "aroma"->soft.aromaTags();default->throw new IllegalArgumentException("Invalid content feature");};
            require(selected!=null && selected.contains(atom) && tokens(product).contains(feature));label=switch(kind){case "category"->"주종 일치";case "taste"->"맛 태그 일치";default->"향 태그 일치";};
            node.put("kind",kind.equals("category")?"category_match":kind+"_match");node.put("label",label);node.put("actual_value",atom);node.put("target_value",atom);
        }else{
            // Stored preferences have no volume target. Never turn a product fact into one.
            require(component.equals("abv_percent") && feature.equals(component) && numeric && !product.path(component).isNull());
            node.put("kind","abv_comparison");node.put("label","도수 비교 근거");node.set("actual_value",product.get(component));node.set("target_value",mapper.valueToTree(soft.targetAbvPercent()));node.put("unit","vol%");
        }
        node.set("contribution",part.get("contribution"));return node;
    }
    private void explanation(JsonNode value,JsonNode product,UserPreferencesResponse preferences,boolean numeric,List<JsonNode> parts,BigDecimal score,boolean allZero,boolean supported,JsonNode fallback){
        require(fields(value,"version","basis","comparison","score_evidence","total_positive_contributions","score_evidence_truncated","filter_evidence","fallback_reason"));
        require(text(value.get("version"),"r04-facts-v1") && fields(value.get("comparison"),"kind") && text(value.path("comparison").get("kind"),"profile") && equal(value.get("fallback_reason"),fallback));
        String basis=score.signum()>0?"content_similarity":!supported || allZero?"deterministic_baseline":"no_shared_features";require(text(value.get("basis"),basis));
        require(integer(value.get("total_positive_contributions"),0,43)==parts.size() && value.path("score_evidence_truncated").isBoolean() && value.path("score_evidence_truncated").booleanValue()==(parts.size()>3));
        var expected=mapper.createArrayNode();parts.stream().limit(3).forEach(part->expected.add(evidence(part,product,preferences,numeric)));require(equal(value.get("score_evidence"),expected));
        var filters=mapper.createArrayNode();filters.addObject().put("kind","availability_filter").put("label","입력 가용 조건 충족").put("actual_value",true);var hard=preferences.hardConstraints();
        if(hard.minPriceKrw()!=null || hard.maxPriceKrw()!=null){var node=filters.addObject();node.put("kind","price_filter");node.put("label","입력 가격 조건 충족");node.set("actual_value",product.get("price_krw"));node.set("minimum",mapper.valueToTree(hard.minPriceKrw()));node.set("maximum",mapper.valueToTree(hard.maxPriceKrw()));node.put("unit","KRW");}
        if(hard.minAbvPercent()!=null || hard.maxAbvPercent()!=null){var node=filters.addObject();node.put("kind","abv_filter");node.put("label","입력 도수 조건 충족");node.set("actual_value",product.get("abv_percent"));node.set("minimum",mapper.valueToTree(hard.minAbvPercent()));node.set("maximum",mapper.valueToTree(hard.maxAbvPercent()));node.put("unit","vol%");}
        require(equal(value.get("filter_evidence"),filters));try{require(mapper.writeValueAsBytes(value).length<=4096);}catch(com.fasterxml.jackson.core.JsonProcessingException invalid){throw new IllegalArgumentException("Invalid content explanation");}
    }
}
