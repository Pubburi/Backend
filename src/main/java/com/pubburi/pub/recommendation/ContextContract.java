package com.pubburi.pub.recommendation;

import static com.pubburi.pub.recommendation.ContentWire.*;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pubburi.pub.controller.response.UserPreferencesResponse;
import com.pubburi.pub.controller.response.UserPreferencesResponse.*;

/** Validates R07's common candidate boundary and each actual winning reference with existing R04 checks. */
public final class ContextContract {
    private final ObjectMapper mapper;private final ContentContract content;
    public ContextContract(ObjectMapper mapper){this.mapper=mapper;content=new ContentContract(mapper);}
    public record Checked(JsonNode result,List<Integer> ids){ }

    public static List<Integer> excludedByFeedback(JsonNode catalog,FeedbackSignals signals){
        var groups=new HashSet<String>();var known=new HashMap<Integer,JsonNode>();
        for(var row:catalog)known.put(integer(row.get("product_id"),1,Integer.MAX_VALUE),row);
        for(int id:signals.notInterested()){require(known.containsKey(id));groups.add(known.get(id).path("sku_group").textValue());}
        return known.entrySet().stream().filter(row->groups.contains(row.getValue().path("sku_group").textValue()))
            .map(Map.Entry::getKey).sorted().toList();
    }

    public Checked validate(JsonNode root,JsonNode catalog,List<Integer> referenceIds,PreferenceHardResponse hard,
            List<Integer> negativeIds,int limit){
        require(fields(root,"algorithm","aggregation_algorithm","fit_scope","profile_source","reference_group_policy",
            "representation_fitted","model_fingerprint","min_relevance_score","references","evaluated_reference_count",
            "eligible_count","excluded_reference_group_count","items","matches","fallback_reason"));
        require(text(root.get("algorithm"),"b1-tfidf-minmax-v1") && text(root.get("aggregation_algorithm"),"r07-max-reference-v1")
            && text(root.get("fit_scope"),"request_catalog") && text(root.get("profile_source"),"explicit_context_product_facts")
            && text(root.get("reference_group_policy"),"exclude_all_known_reference_groups")
            && root.path("min_relevance_score").isNumber() && root.path("min_relevance_score").decimalValue().signum()==0);
        require(!referenceIds.isEmpty() && referenceIds.size()<=20 && new HashSet<>(referenceIds).size()==referenceIds.size());
        var byId=new HashMap<Integer,JsonNode>();
        for(var row:catalog)require(byId.put(integer(row.get("product_id"),1,Integer.MAX_VALUE),row)==null);
        var groups=new HashSet<String>();
        for(int id:referenceIds){var row=byId.get(id);require(row!=null && row.path("available").booleanValue());groups.add(row.path("sku_group").textValue());}
        var excluded=new HashSet<Integer>(negativeIds);require(excluded.size()==negativeIds.size() && byId.keySet().containsAll(excluded));
        int excludedGroupCount=0;
        for(var row:byId.entrySet())if(groups.contains(row.getValue().path("sku_group").textValue())){excluded.add(row.getKey());excludedGroupCount++;}
        var preferences=new UserPreferencesResponse("NOT_STARTED",new PreferenceSoftResponse(null,null,null,null),hard);
        int eligible=(int)byId.entrySet().stream().filter(row->!excluded.contains(row.getKey()) && ContentContract.eligible(row.getValue(),preferences)).count();
        require(integer(root.get("evaluated_reference_count"),1,20)==referenceIds.size()
            && integer(root.get("eligible_count"),0,1000)==eligible
            && integer(root.get("excluded_reference_group_count"),1,1000)==excludedGroupCount);
        var references=root.path("references");require(references.isArray() && references.size()==referenceIds.size());
        var spans=ContentContract.numericSpans(catalog);var matching=new HashSet<Integer>();int informative=0;
        for(int i=0;i<references.size();i++){
            var actual=references.get(i);int id=referenceIds.get(i);var reference=byId.get(id);
            require(fields(actual,"product_id","sku_group","state","supported_profile_tokens","unknown_profile_tokens","active_numeric_preferences","fallback_reason")
                && integer(actual.get("product_id"),1,Integer.MAX_VALUE)==id && equal(actual.get("sku_group"),reference.get("sku_group")) && text(actual.get("state"),"available"));
            var tokens=tokens(reference);var targets=ContentContract.referenceTargets(reference,spans);
            require(integer(actual.get("supported_profile_tokens"),0,41)==tokens.size()
                && integer(actual.get("unknown_profile_tokens"),0,41)==0
                && equal(actual.get("active_numeric_preferences"),mapper.valueToTree(List.copyOf(targets.keySet()))));
            boolean supported=!tokens.isEmpty() || !targets.isEmpty();if(supported)informative++;
            var fallback=actual.get("fallback_reason");
            if(eligible==0)require(text(fallback,"no_eligible_products"));
            else if(!supported)require(text(fallback,"no_supported_preferences"));
            else if(fallback.isNull())matching.add(id);
            else require(text(fallback,"no_matching_features"));
        }
        var items=root.path("items");var matches=root.path("matches");
        require(items.isArray() && matches.isArray() && matches.size()==items.size() && items.size()<=limit
            && (!items.isEmpty() || matching.isEmpty()));
        String fallback=eligible==0?"no_eligible_products":informative==0?"no_supported_preferences":items.isEmpty()?"no_matching_features":null;
        require(equal(root.get("fallback_reason"),mapper.valueToTree(fallback)));
        var winners=new HashMap<Integer,JsonNode>();
        for(int i=0;i<items.size();i++){
            int id=integer(items.get(i).get("product_id"),1,Integer.MAX_VALUE);var match=matches.get(i);
            require(fields(match,"product_id","reference_product_id") && integer(match.get("product_id"),1,Integer.MAX_VALUE)==id);
            int winner=integer(match.get("reference_product_id"),1,Integer.MAX_VALUE);
            require(matching.contains(winner) && winners.put(id,byId.get(winner))==null);
        }
        // Reuse the candidate/score/order/contribution/typed explanation boundary, with no inferred profile.
        ObjectNode base=mapper.createObjectNode();
        for(String field:List.of("algorithm","fit_scope","representation_fitted","model_fingerprint","items","eligible_count"))base.set(field,root.get(field));
        base.put("supported_profile_tokens",0).put("unknown_profile_tokens",0).putArray("active_numeric_preferences");
        if(eligible==0)base.put("fallback_reason","no_eligible_products");
        else if(items.isEmpty())base.put("fallback_reason","no_matching_features");else base.putNull("fallback_reason");
        var checked=content.validate(base,catalog,preferences,limit,excluded,winners,true);
        return new Checked(root.deepCopy(),checked.items().stream().map(item->item.productId()).toList());
    }
}
