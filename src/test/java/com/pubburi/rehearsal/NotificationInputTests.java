package com.pubburi.rehearsal;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;

class NotificationInputTests {
    final ObjectMapper json=new ObjectMapper();
    @Test void explicitPageIdsPreserveOrderAndAcceptTheRealHundredItemBoundary()throws Exception{
        var ids=new ArrayList<String>();for(int i=0;i<100;i++)ids.add(UUID.randomUUID().toString());
        var read=json.readValue(json.writeValueAsString(Map.of("ids",ids)),NotificationInput.Read.class);
        assertEquals(ids,read.ids().stream().map(UUID::toString).toList());
        ids.add(UUID.randomUUID().toString());assertThrows(Exception.class,()->json.readValue(json.writeValueAsString(Map.of("ids",ids)),NotificationInput.Read.class));
    }
    @Test void ambiguousOrCoercedReadsCannotBecomeAnImplicitReadAll(){
        String id="abcdefab-0000-4000-8000-000000000001";
        for(String text:List.of("null","[]","{}","{\"ids\":[]}","{\"ids\":null}","{\"ids\":[true]}","{\"ids\":[1]}",
                "{\"ids\":[\""+id+"\",\""+id+"\"]}","{\"ids\":[\""+id.toUpperCase()+"\"]}",
                "{\"ids\":[\"00000000-0-4000-8000-1\"]}","{\"ids\":[\""+id+"\"],\"owner\":\"other\"}",
                "{\"ids\":[\""+id+"\"],\"ids\":[\""+id+"\"]}","{\"ids\":[\""+id+"\"]} {}"))
            assertThrows(Exception.class,()->json.readValue(text,NotificationInput.Read.class),text);
    }
}
