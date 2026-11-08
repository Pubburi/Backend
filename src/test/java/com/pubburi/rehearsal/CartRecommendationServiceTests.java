package com.pubburi.rehearsal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.Instant;
import java.util.*;
import com.fasterxml.jackson.databind.*;
import com.pubburi.pub.controller.response.*;
import com.pubburi.pub.controller.response.CartRecommendationResponse.*;
import com.pubburi.pub.controller.response.ContentRecommendationResponse.*;
import com.pubburi.pub.controller.response.UserPreferencesResponse.*;
import com.pubburi.pub.model.service.OwnedContentSnapshotService;
import com.pubburi.pub.recommendation.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class CartRecommendationServiceTests {
    final ObjectMapper json=new ObjectMapper();final CartContextSnapshotService snapshots=mock(CartContextSnapshotService.class);
    final ContentCatalogSource source=mock(ContentCatalogSource.class);final ContextGateway provider=mock(ContextGateway.class);
    final CartRecommendationService service=new CartRecommendationService(snapshots,source,provider,json);
    final PreferenceHardResponse hard=new PreferenceHardResponse(1000,2000,null,null);
    JsonNode sample()throws Exception{try(var input=getClass().getResourceAsStream("/recommendation/context-contract.json")){return json.readTree(input);}}
    ProductResponse product(int id){
        int price=switch(id){case 20,30->1500;case 40->1700;case 50->1800;case 60->2500;default->1000;};
        boolean wine=List.of(20,40,60).contains(id);return new ProductResponse(id,"Current "+id,wine?"authored-wine":"authored-beer",price,null,wine?12:5,0);
    }
    ContentCatalogSource.Catalog catalog(JsonNode data){
        var bindings=new LinkedHashMap<Integer,ContentCatalogSource.Binding>();for(var row:data){int id=row.path("product_id").intValue();var p=json.valueToTree(product(id));bindings.put(id,new ContentCatalogSource.Binding(id,p,json.createObjectNode()));}
        return new ContentCatalogSource.Catalog(data,bindings,Instant.now().plusSeconds(300),"a".repeat(64),CatalogScope.REVIEWED_DEMO);
    }
    CartContextSnapshotService.Snapshot snapshot(ContentCatalogSource.Catalog catalog,CartService.Cart cart,FeedbackSignals signals,PreferenceHardResponse bounds){
        var facts=new LinkedHashMap<Integer,OwnedContentSnapshotService.Facts>();for(var binding:catalog.bindings().values())facts.put(binding.productId(),new OwnedContentSnapshotService.Facts(binding.product(),binding.features(),product(binding.productId())));
        return new CartContextSnapshotService.Snapshot(cart,new OwnedContentSnapshotService.Snapshot(new UserPreferencesResponse("COMPLETED",new PreferenceSoftResponse(null,null,null,null),bounds),facts,null,signals));
    }
    CartService.Cart cart(long version){return new CartService.Cart(version,List.of(new CartService.Item(10,"Old name",product(10),3),new CartService.Item(20,"Old other",product(20),1)));}
    @Test void quantitiesStayInCurrentReferenceMetadataAndNeverBecomeProviderWeights()throws Exception{
        var data=sample();var catalog=catalog(data.get("catalog"));var before=snapshot(catalog,cart(4),new FeedbackSignals(List.of(),List.of(50)),hard);
        when(source.current()).thenReturn(Optional.of(catalog));when(snapshots.read(CartContractTests.OWNER,catalog.ids())).thenReturn(before);
        when(provider.context(catalog.catalog(),List.of(10,20),hard,List.of(50),4)).thenReturn(Optional.of(data.get("response")));
        var result=service.recommend(CartContractTests.OWNER,new CartInput.Recommendation(4,4));
        assertEquals(Source.PROVIDER,result.source());assertEquals(4,result.cartVersion());assertNull(result.unavailableReason());
        assertEquals(List.of(30,40),result.products().stream().map(ProductResponse::id).toList());assertEquals(3,result.references().get(0).quantity());assertEquals("Current 10",result.references().get(0).name());
        verify(provider).context(catalog.catalog(),List.of(10,20),hard,List.of(50),4);verify(snapshots,times(2)).read(CartContractTests.OWNER,catalog.ids());
    }
    @Test void everyPostRemoteCartHardNegativeProductOrEpochChangeWinsOverUnavailable()throws Exception{
        var data=sample();var catalog=catalog(data.get("catalog"));var before=snapshot(catalog,cart(4),FeedbackSignals.EMPTY,hard);
        when(source.current()).thenReturn(Optional.of(catalog));when(provider.context(any(),anyList(),any(),anyList(),anyInt())).thenReturn(Optional.empty());
        var bounds=new PreferenceHardResponse(null,0,null,null);
        var productChanged=new LinkedHashMap<>(before.content().products());var old=productChanged.get(30);productChanged.put(30,new OwnedContentSnapshotService.Facts(json.createObjectNode().put("id",30),old.features(),old.currentProduct()));
        var contentChanged=new OwnedContentSnapshotService.Snapshot(before.content().preferences(),productChanged,null,FeedbackSignals.EMPTY);
        for(var after:List.of(snapshot(catalog,cart(5),FeedbackSignals.EMPTY,hard),snapshot(catalog,cart(4),new FeedbackSignals(List.of(),List.of(50)),hard),snapshot(catalog,cart(4),FeedbackSignals.EMPTY,bounds),new CartContextSnapshotService.Snapshot(cart(4),contentChanged))){
            when(snapshots.read(CartContractTests.OWNER,catalog.ids())).thenReturn(before,after);
            assertEquals(HttpStatus.CONFLICT,assertThrows(ResponseStatusException.class,()->service.recommend(CartContractTests.OWNER,new CartInput.Recommendation(4,4))).getStatusCode());
        }
        when(snapshots.read(CartContractTests.OWNER,catalog.ids())).thenReturn(before).thenThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        assertEquals(HttpStatus.UNAUTHORIZED,assertThrows(ResponseStatusException.class,()->service.recommend(CartContractTests.OWNER,new CartInput.Recommendation(4,4))).getStatusCode());
    }
    @Test void unsupportedReferencesRemainCompleteAndNeverReachTheProvider()throws Exception{
        var catalog=catalog(sample().get("catalog"));when(source.current()).thenReturn(Optional.of(catalog));
        var many=new ArrayList<CartService.Item>();for(int id=1;id<=21;id++)many.add(new CartService.Item(id,"Old",product(id),1));
        for(var entry:Map.of(new CartService.Cart(0,List.of()),UnavailableReason.EMPTY_CART,new CartService.Cart(1,many),UnavailableReason.REFERENCE_LIMIT,
                new CartService.Cart(2,List.of(new CartService.Item(999,"Deleted",null,2))),UnavailableReason.DELETED_ITEMS,
                new CartService.Cart(3,List.of(new CartService.Item(999,"Old",product(999),2))),UnavailableReason.UNREVIEWED_ITEMS).entrySet()){
            when(snapshots.read(CartContractTests.OWNER,catalog.ids())).thenReturn(snapshot(catalog,entry.getKey(),FeedbackSignals.EMPTY,hard));
            var result=service.recommend(CartContractTests.OWNER,new CartInput.Recommendation(entry.getKey().version(),4));
            assertEquals(Source.UNAVAILABLE,result.source());assertEquals(entry.getValue(),result.unavailableReason());assertEquals(entry.getKey().items().size(),result.references().size());assertTrue(result.products().isEmpty());assertNull(result.result());
            if(entry.getValue()==UnavailableReason.DELETED_ITEMS)assertNull(result.references().get(0).name());
        }
        verifyNoInteractions(provider);
    }
    @Test void expiredOrReplacedCatalogCannotKeepOldReadyReferenceClaims()throws Exception{
        var data=sample();var catalog=catalog(data.get("catalog"));var before=snapshot(catalog,cart(4),FeedbackSignals.EMPTY,hard);
        when(snapshots.read(CartContractTests.OWNER,catalog.ids())).thenReturn(before);when(provider.context(any(),anyList(),any(),anyList(),anyInt())).thenReturn(Optional.empty());
        when(source.current()).thenReturn(Optional.of(catalog),Optional.empty());
        var expired=service.recommend(CartContractTests.OWNER,new CartInput.Recommendation(4,4));assertEquals(UnavailableReason.CATALOG_UNAVAILABLE,expired.unavailableReason());assertTrue(expired.references().stream().allMatch(ref->ref.state()==ReferenceState.CATALOG_UNAVAILABLE));
        var replacement=new ContentCatalogSource.Catalog(catalog.catalog(),catalog.bindings(),catalog.validUntil(),"b".repeat(64),catalog.scope());
        when(source.current()).thenReturn(Optional.of(catalog),Optional.of(replacement));
        assertEquals(HttpStatus.CONFLICT,assertThrows(ResponseStatusException.class,()->service.recommend(CartContractTests.OWNER,new CartInput.Recommendation(4,4))).getStatusCode());
    }
}
