package com.pubburi.rehearsal;

import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pubburi.pub.controller.auth.SessionUser;
import com.pubburi.pub.controller.response.CartRecommendationResponse;
import com.pubburi.pub.controller.response.CartRecommendationResponse.*;
import com.pubburi.pub.controller.response.ContentRecommendationResponse.CatalogScope;
import com.pubburi.pub.controller.response.ContentRecommendationResponse.Source;
import com.pubburi.pub.model.service.OwnedContentSnapshotService;
import com.pubburi.pub.recommendation.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class CartRecommendationService {
    private final CartContextSnapshotService snapshots;private final ContentCatalogSource catalogs;
    private final ContextGateway provider;private final ContextContract contract;private final ObjectMapper mapper;
    public CartRecommendationService(CartContextSnapshotService snapshots,ContentCatalogSource catalogs,ContextGateway provider,ObjectMapper mapper){
        this.snapshots=snapshots;this.catalogs=catalogs;this.provider=provider;this.mapper=mapper;contract=new ContextContract(mapper);
    }
    public CartRecommendationResponse recommend(SessionUser owner,CartInput.Recommendation input){
        var approved=catalogs.current().filter(catalog->catalog.scope()==CatalogScope.REVIEWED_DEMO);
        var ids=approved.map(ContentCatalogSource.Catalog::ids).orElse(List.of());
        var before=snapshots.read(owner,ids);
        if(before.cart().version()!=input.expectedVersion())changed();
        var references=references(before.cart(),approved);
        var reason=unavailable(references,approved.isPresent());
        if(reason!=null)return unavailable(before.cart(),references,reason);

        var catalog=approved.orElseThrow();var referenceIds=references.stream().map(Reference::productId).toList();
        requireCurrent(catalog,before);
        var negativeIds=ContextContract.excludedByFeedback(catalog.catalog(),before.content().feedback());
        var response=provider.context(catalog.catalog(),referenceIds,before.content().preferences().hardConstraints(),negativeIds,input.limit());
        // Remote work owns no DB transaction or cart/member lock. Every result is rechecked, including failures.
        var current=snapshots.read(owner,ids);
        if(!ContentWire.equal(mapper.valueToTree(before.cart()),mapper.valueToTree(current.cart()))
                || !ContentWire.equal(mapper.valueToTree(before.content().preferences().hardConstraints()),mapper.valueToTree(current.content().preferences().hardConstraints()))
                || !before.content().feedback().notInterested().equals(current.content().feedback().notInterested()))changed();
        requireCurrent(catalog,current);
        var after=catalogs.current().filter(value->value.scope()==CatalogScope.REVIEWED_DEMO);
        if(after.isEmpty())return unavailable(current.cart(),references(current.cart(),Optional.empty()),UnavailableReason.CATALOG_UNAVAILABLE);
        if(!catalog.sha256().equals(after.get().sha256()))changed();
        if(response.isEmpty())return unavailable(current.cart(),references,UnavailableReason.PROVIDER_UNAVAILABLE);
        try{
            var result=contract.validate(response.get(),catalog.catalog(),referenceIds,current.content().preferences().hardConstraints(),negativeIds,input.limit());
            return new CartRecommendationResponse(Source.PROVIDER,CatalogScope.REVIEWED_DEMO,current.cart().version(),references,null,result.result(),
                result.ids().stream().map(id->current.content().products().get(id).currentProduct()).toList());
        }catch(IllegalArgumentException invalid){return unavailable(current.cart(),references,UnavailableReason.PROVIDER_UNAVAILABLE);}
    }
    private List<Reference> references(CartService.Cart cart,Optional<ContentCatalogSource.Catalog> catalog){
        var byId=new HashMap<Integer,com.fasterxml.jackson.databind.JsonNode>();
        catalog.ifPresent(value->value.catalog().forEach(row->byId.put(row.path("product_id").intValue(),row)));
        return cart.items().stream().map(item->{
            var product=item.currentProduct();var row=byId.get(item.productId());
            var state=product==null?ReferenceState.DELETED:catalog.isEmpty()?ReferenceState.CATALOG_UNAVAILABLE:
                row==null?ReferenceState.UNREVIEWED:!row.path("available").booleanValue()?ReferenceState.UNAVAILABLE:ReferenceState.READY;
            return new Reference(item.productId(),product==null?null:product.name(),item.quantity(),state);
        }).toList();
    }
    private UnavailableReason unavailable(List<Reference> refs,boolean catalog){
        if(refs.isEmpty())return UnavailableReason.EMPTY_CART;
        if(refs.size()>20)return UnavailableReason.REFERENCE_LIMIT;
        if(refs.stream().anyMatch(ref->ref.state()==ReferenceState.DELETED))return UnavailableReason.DELETED_ITEMS;
        if(!catalog)return UnavailableReason.CATALOG_UNAVAILABLE;
        if(refs.stream().anyMatch(ref->ref.state()==ReferenceState.UNREVIEWED))return UnavailableReason.UNREVIEWED_ITEMS;
        if(refs.stream().anyMatch(ref->ref.state()==ReferenceState.UNAVAILABLE))return UnavailableReason.UNAVAILABLE_REFERENCES;
        return null;
    }
    private CartRecommendationResponse unavailable(CartService.Cart cart,List<Reference> refs,UnavailableReason reason){
        return new CartRecommendationResponse(Source.UNAVAILABLE,CatalogScope.REVIEWED_DEMO,cart.version(),refs,reason,null,List.of());
    }
    private void requireCurrent(ContentCatalogSource.Catalog catalog,CartContextSnapshotService.Snapshot snapshot){
        if(!OwnedContentSnapshotService.matches(catalog,snapshot.content().products()))changed();
    }
    private void changed(){throw new ResponseStatusException(HttpStatus.CONFLICT,"Cart recommendation inputs changed; reload before requesting again");}
}
