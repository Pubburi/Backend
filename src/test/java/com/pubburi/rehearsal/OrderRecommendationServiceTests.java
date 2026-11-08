package com.pubburi.rehearsal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.Instant;
import java.util.*;
import com.fasterxml.jackson.databind.*;
import com.pubburi.pub.controller.response.*;
import com.pubburi.pub.controller.response.CartRecommendationResponse.ReferenceState;
import com.pubburi.pub.controller.response.ContentRecommendationResponse.*;
import com.pubburi.pub.controller.response.OrderRecommendationResponse.UnavailableReason;
import com.pubburi.pub.controller.response.UserPreferencesResponse.*;
import com.pubburi.pub.model.service.OwnedContentSnapshotService;
import com.pubburi.pub.recommendation.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class OrderRecommendationServiceTests {
    final ObjectMapper json=new ObjectMapper();final OrderContextSnapshotService snapshots=mock(OrderContextSnapshotService.class);
    final ContentCatalogSource source=mock(ContentCatalogSource.class);final ContextGateway provider=mock(ContextGateway.class);
    final OrderRecommendationService service=new OrderRecommendationService(snapshots,source,provider,json);
    final PreferenceHardResponse hard=new PreferenceHardResponse(1000,2000,null,null);
    JsonNode sample()throws Exception{try(var input=getClass().getResourceAsStream("/recommendation/context-contract.json")){return json.readTree(input);}}
    ContentCatalogSource.Catalog catalog(JsonNode data){
        var bindings=new LinkedHashMap<Integer,ContentCatalogSource.Binding>();
        for(var row:data){int id=row.path("product_id").intValue();var p=new ProductResponse(id,"Current "+id,row.path("category").textValue(),row.path("price_krw").intValue(),null,row.path("abv_percent").floatValue(),0);bindings.put(id,new ContentCatalogSource.Binding(id,json.valueToTree(p),json.createObjectNode()));}
        return new ContentCatalogSource.Catalog(data,bindings,Instant.now().plusSeconds(300),"a".repeat(64),CatalogScope.REVIEWED_DEMO);
    }
    OrderResponse order(List<OrderDetailResponse> details){return new OrderResponse(7,"own","TAKEOUT",null,"N",new Date(1700000000000L),details,777,"ORDER_SNAPSHOT");}
    OrderDetailResponse row(int id,Integer product,int quantity){return new OrderDetailResponse(id,7,product,quantity,"Historical snapshot",999,777,"Historical type",null);}
    OrderContextSnapshotService.Snapshot snapshot(ContentCatalogSource.Catalog catalog,OrderResponse order,int count,FeedbackSignals signals,PreferenceHardResponse bounds){
        var facts=new LinkedHashMap<Integer,OwnedContentSnapshotService.Facts>();var current=new HashMap<Integer,ProductResponse>();
        for(var binding:catalog.bindings().values()){var p=json.convertValue(binding.product(),ProductResponse.class);facts.put(binding.productId(),new OwnedContentSnapshotService.Facts(binding.product(),binding.features(),p));current.put(binding.productId(),p);}
        return new OrderContextSnapshotService.Snapshot(order,count,current,new OwnedContentSnapshotService.Snapshot(new UserPreferencesResponse("COMPLETED",new PreferenceSoftResponse(null,null,null,null),bounds),facts,null,signals));
    }
    @Test void completeOriginalRowsKeepQuantitiesButTheProviderSeesOnlyUniqueCurrentIdsAndFacts()throws Exception{
        var data=sample();var catalog=catalog(data.get("catalog"));var order=order(List.of(row(101,20,Integer.MAX_VALUE),row(102,10,1),row(103,20,2)));
        var before=snapshot(catalog,order,3,new FeedbackSignals(List.of(),List.of(50)),hard);
        when(source.current()).thenAnswer(ignored->Optional.of(catalog));when(snapshots.read(NotebookContractTests.OWNER,7,catalog.ids(),null)).thenReturn(before);
        when(provider.context(catalog.catalog(),List.of(10,20),hard,List.of(50),4)).thenReturn(Optional.of(data.get("response")));
        var result=service.recommend(NotebookContractTests.OWNER,7,new OrderRecommendationInput(4));assertEquals(Source.PROVIDER,result.source());assertEquals(3,result.referenceCount());assertEquals(7,result.orderId());
        assertEquals(List.of(101,102,103),result.references().stream().map(OrderRecommendationResponse.Reference::detailId).toList());assertEquals(List.of(20,10,20),result.references().stream().map(OrderRecommendationResponse.Reference::productId).toList());assertEquals(Integer.MAX_VALUE,result.references().get(0).quantity());assertEquals("Current 20",result.references().get(0).name());
        assertEquals(List.of(30,40),result.products().stream().map(ProductResponse::id).toList());assertNull(result.unavailableReason());
        verify(provider).context(catalog.catalog(),List.of(10,20),hard,List.of(50),4);verify(snapshots,times(2)).read(NotebookContractTests.OWNER,7,catalog.ids(),null);
    }
    @Test void oversizedEmptyDeletedAndUnreviewedOrdersNeverBecomePartialRecommendations()throws Exception{
        var catalog=catalog(sample().get("catalog"));when(source.current()).thenAnswer(ignored->Optional.of(catalog));
        var unknown=row(103,999,2);var current=new ProductResponse(999,"Unreviewed current","other",123,null,0,0);
        for(var entry:Map.of(order(List.of()),UnavailableReason.EMPTY_ORDER,order(List.of(row(101,null,2))),UnavailableReason.DELETED_ITEMS,order(List.of(unknown)),UnavailableReason.UNREVIEWED_ITEMS).entrySet()){
            var before=snapshot(catalog,entry.getKey(),entry.getKey().details().size(),FeedbackSignals.EMPTY,hard);
            if(entry.getValue()==UnavailableReason.UNREVIEWED_ITEMS){var products=new HashMap<>(before.currentProducts());products.put(999,current);before=new OrderContextSnapshotService.Snapshot(before.order(),1,products,before.content());}
            when(snapshots.read(NotebookContractTests.OWNER,7,catalog.ids(),null)).thenReturn(before);
            var result=service.recommend(NotebookContractTests.OWNER,7,new OrderRecommendationInput(4));assertEquals(entry.getValue(),result.unavailableReason());assertEquals(before.referenceCount(),result.references().size());assertNull(result.result());assertTrue(result.products().isEmpty());
            if(entry.getValue()==UnavailableReason.DELETED_ITEMS){assertNull(result.references().get(0).productId());assertNull(result.references().get(0).name());}
        }
        when(snapshots.read(NotebookContractTests.OWNER,7,catalog.ids(),null)).thenReturn(snapshot(catalog,order(List.of()),21,FeedbackSignals.EMPTY,hard));
        var over=service.recommend(NotebookContractTests.OWNER,7,new OrderRecommendationInput(4));assertEquals(UnavailableReason.REFERENCE_LIMIT,over.unavailableReason());assertEquals(21,over.referenceCount());assertTrue(over.references().isEmpty());verifyNoInteractions(provider);
    }
    @Test void changesAndRevocationWinEvenOverAProviderOutage()throws Exception{
        var catalog=catalog(sample().get("catalog"));var order=order(List.of(row(101,10,1)));var before=snapshot(catalog,order,1,FeedbackSignals.EMPTY,hard);
        when(source.current()).thenAnswer(ignored->Optional.of(catalog));when(provider.context(any(),anyList(),any(),anyList(),anyInt())).thenReturn(Optional.empty());
        var completed=new OrderResponse(7,"own","TAKEOUT",null,"Y",order.orderTime(),order.details(),777,"ORDER_SNAPSHOT");
        for(var after:List.of(snapshot(catalog,completed,1,FeedbackSignals.EMPTY,hard),snapshot(catalog,order,1,new FeedbackSignals(List.of(),List.of(50)),hard),snapshot(catalog,order,1,FeedbackSignals.EMPTY,new PreferenceHardResponse(null,0,null,null)))){
            when(snapshots.read(NotebookContractTests.OWNER,7,catalog.ids(),null)).thenReturn(before,after);
            assertEquals(HttpStatus.CONFLICT,assertThrows(ResponseStatusException.class,()->service.recommend(NotebookContractTests.OWNER,7,new OrderRecommendationInput(4))).getStatusCode());
        }
        doReturn(before).doThrow(new ResponseStatusException(HttpStatus.NOT_FOUND)).when(snapshots).read(NotebookContractTests.OWNER,7,catalog.ids(),null);
        assertEquals(HttpStatus.CONFLICT,assertThrows(ResponseStatusException.class,()->service.recommend(NotebookContractTests.OWNER,7,new OrderRecommendationInput(4))).getStatusCode());
        doReturn(before).doThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED)).when(snapshots).read(NotebookContractTests.OWNER,7,catalog.ids(),null);
        assertEquals(HttpStatus.UNAUTHORIZED,assertThrows(ResponseStatusException.class,()->service.recommend(NotebookContractTests.OWNER,7,new OrderRecommendationInput(4))).getStatusCode());
    }
}
