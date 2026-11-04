package com.pubburi.pub.model.service;

import static com.pubburi.pub.recommendation.ContentWire.*;
import java.util.*;
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
    public ContentRecommendationResponse recommend(String id,String epoch,int limit){
        if(limit<1 || limit>12)throw new IllegalArgumentException("Recommendation limit must be between 1 and 12");
        var approved=catalogs.current();var before=snapshots.read(id,epoch,approved.map(ContentCatalogSource.Catalog::ids).orElse(List.of()));
        if(approved.isEmpty())return ContentRecommendationResponse.unavailable();
        var catalog=approved.get();requireCurrent(catalog,before);
        var response=gateway.recommend(catalog.catalog(),before.preferences(),limit);
        var current=snapshots.read(id,epoch,catalog.ids());
        if(!equal(mapper.valueToTree(before.preferences()),mapper.valueToTree(current.preferences())))changed();
        requireCurrent(catalog,current);
        var after=catalogs.current();
        if(after.isEmpty())return ContentRecommendationResponse.unavailable();
        if(!catalog.sha256().equals(after.get().sha256()))changed();
        if(response.isPresent()){
            try{
                var result=contract.validate(response.get(),catalog.catalog(),before.preferences(),limit);
                return new ContentRecommendationResponse(Source.PROVIDER,catalog.scope(),result,
                    result.items().stream().map(item->current.products().get(item.productId()).currentProduct()).toList());
            }catch(IllegalArgumentException invalid){/* No local score, replacement catalog or weaker B0 result. */}
        }
        return ContentRecommendationResponse.unavailable();
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
