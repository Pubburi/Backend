package com.pubburi.rehearsal;

import java.util.*;
import com.fasterxml.jackson.databind.*;
import com.pubburi.pub.controller.auth.SessionUser;
import com.pubburi.pub.controller.response.*;
import com.pubburi.pub.controller.response.ContentRecommendationResponse.*;
import com.pubburi.pub.controller.response.CartRecommendationResponse.ReferenceState;
import com.pubburi.pub.controller.response.OrderRecommendationResponse.*;
import com.pubburi.pub.model.service.OwnedContentSnapshotService;
import com.pubburi.pub.recommendation.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class OrderRecommendationService {
    private final OrderContextSnapshotService snapshots;private final ContentCatalogSource catalogs;private final ContextGateway provider;private final ContextContract contract;private final ObjectMapper mapper;
    public OrderRecommendationService(OrderContextSnapshotService snapshots,ContentCatalogSource catalogs,ContextGateway provider,ObjectMapper mapper){this.snapshots=snapshots;this.catalogs=catalogs;this.provider=provider;this.mapper=mapper;contract=new ContextContract(mapper);}
    public OrderRecommendationResponse recommend(SessionUser owner,int orderId,OrderRecommendationInput input){
        if(orderId<1 || input==null || input.limit()<1 || input.limit()>12)throw new IllegalArgumentException("Invalid order recommendation input");
        var approved=catalogs.current().filter(value->Set.of(CatalogScope.REVIEWED_DEMO,CatalogScope.REVIEWED_LOCAL_CATALOG).contains(value.scope()));
        var ids=approved.map(ContentCatalogSource.Authority::ids).orElse(List.of());var namespace=approved.map(ContentCatalogSource.Authority::businessSourceId).orElse(null);
        var before=snapshots.read(owner,orderId,ids,namespace);var scope=approved.map(ContentCatalogSource.Authority::scope).orElse(CatalogScope.REVIEWED_DEMO);
        if(before.referenceCount()==0)return unavailable(before,List.of(),UnavailableReason.EMPTY_ORDER,scope);
        if(before.referenceCount()>20)return unavailable(before,List.of(),UnavailableReason.REFERENCE_LIMIT,scope);
        var selected=approved.map(authority->authority instanceof ContentCatalogSource.Catalog catalog?catalog:authority.resolve(before.content().products()).orElseThrow(()->new ResponseStatusException(HttpStatus.CONFLICT,"Reviewed catalog facts changed")));
        var references=references(before,selected);var reason=unavailable(references,selected.isPresent());
        if(reason!=null)return unavailable(before,references,reason,scope);
        var catalog=selected.orElseThrow();var referenceIds=references.stream().map(Reference::productId).distinct().sorted().toList();
        if(!OwnedContentSnapshotService.matches(catalog,before.content().products()))throw changed();
        var excluded=ContextContract.excludedByFeedback(catalog.catalog(),before.content().feedback());
        // Reuse the cart's provider and factual contract; no order price/name/quantity is a model feature.
        var response=provider.context(catalog.catalog(),referenceIds,before.content().preferences().hardConstraints(),excluded,input.limit());
        OrderContextSnapshotService.Snapshot current;
        try{current=snapshots.read(owner,orderId,ids,namespace);}catch(ResponseStatusException missing){if(missing.getStatusCode().value()==404)throw changed();throw missing;}
        if(!ContentWire.equal(mapper.valueToTree(before.order()),mapper.valueToTree(current.order())) || before.referenceCount()!=current.referenceCount()
                || !ContentWire.equal(mapper.valueToTree(before.currentProducts()),mapper.valueToTree(current.currentProducts()))
                || !ContentWire.equal(mapper.valueToTree(before.content().preferences().hardConstraints()),mapper.valueToTree(current.content().preferences().hardConstraints()))
                || !before.content().feedback().notInterested().equals(current.content().feedback().notInterested())
                || !OwnedContentSnapshotService.matches(catalog,current.content().products()))throw changed();
        var after=catalogs.current();
        if(after.isEmpty()){if(scope==CatalogScope.REVIEWED_LOCAL_CATALOG)throw changed();return unavailable(current,references(current,Optional.empty()),UnavailableReason.CATALOG_UNAVAILABLE,scope);}
        if(!catalog.sha256().equals(after.get().sha256()))throw changed();
        if(response.isEmpty())return unavailable(current,references,UnavailableReason.PROVIDER_UNAVAILABLE,scope);
        try{
            var result=contract.validate(response.get(),catalog.catalog(),referenceIds,current.content().preferences().hardConstraints(),excluded,input.limit());
            return new OrderRecommendationResponse(Source.PROVIDER,scope,orderId,current.referenceCount(),references,null,result.result(),result.ids().stream().map(id->current.content().products().get(id).currentProduct()).toList());
        }catch(IllegalArgumentException invalid){return unavailable(current,references,UnavailableReason.PROVIDER_UNAVAILABLE,scope);}
    }
    private List<Reference> references(OrderContextSnapshotService.Snapshot snapshot,Optional<ContentCatalogSource.Catalog> catalog){
        var byId=new HashMap<Integer,JsonNode>();catalog.ifPresent(value->value.catalog().forEach(row->byId.put(row.path("product_id").intValue(),row)));
        return snapshot.order().details().stream().map(detail->{
            var id=detail.productId();var product=id==null?null:snapshot.currentProducts().get(id);var row=id==null?null:byId.get(id);
            var state=id==null?ReferenceState.DELETED:catalog.isEmpty()?ReferenceState.CATALOG_UNAVAILABLE:row==null?ReferenceState.UNREVIEWED:!row.path("available").booleanValue()?ReferenceState.UNAVAILABLE:ReferenceState.READY;
            return new Reference(detail.id(),id,product==null?null:product.name(),detail.quantity(),state);
        }).toList();
    }
    private UnavailableReason unavailable(List<Reference> refs,boolean catalog){
        if(refs.stream().anyMatch(ref->ref.state()==ReferenceState.DELETED))return UnavailableReason.DELETED_ITEMS;
        if(!catalog)return UnavailableReason.CATALOG_UNAVAILABLE;
        if(refs.stream().anyMatch(ref->ref.state()==ReferenceState.UNREVIEWED))return UnavailableReason.UNREVIEWED_ITEMS;
        if(refs.stream().anyMatch(ref->ref.state()==ReferenceState.UNAVAILABLE))return UnavailableReason.UNAVAILABLE_REFERENCES;
        return null;
    }
    private OrderRecommendationResponse unavailable(OrderContextSnapshotService.Snapshot snapshot,List<Reference> refs,UnavailableReason reason,CatalogScope scope){return new OrderRecommendationResponse(Source.UNAVAILABLE,scope,snapshot.order().oId(),snapshot.referenceCount(),refs,reason,null,List.of());}
    private ResponseStatusException changed(){return new ResponseStatusException(HttpStatus.CONFLICT,"Order recommendation inputs changed; read the current order again");}
}
