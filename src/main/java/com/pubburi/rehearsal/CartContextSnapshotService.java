package com.pubburi.rehearsal;

import java.util.List;
import com.pubburi.pub.controller.auth.SessionUser;
import com.pubburi.pub.model.service.OwnedContentSnapshotService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Cart, private exclusions/hard bounds and current approved product facts share one short RR. */
@Service
public class CartContextSnapshotService {
    public record Snapshot(CartService.Cart cart,OwnedContentSnapshotService.Snapshot content) { }
    private final CartService carts;private final OwnedContentSnapshotService contents;
    public CartContextSnapshotService(CartService carts,OwnedContentSnapshotService contents){this.carts=carts;this.contents=contents;}
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Snapshot read(SessionUser owner,List<Integer> ids){
        return read(owner,ids,null);
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Snapshot read(SessionUser owner,List<Integer> ids,String businessSourceId){
        return new Snapshot(carts.get(owner),businessSourceId==null?contents.read(owner.id(),owner.authEpoch(),ids,List.of(),true)
            :contents.read(owner.id(),owner.authEpoch(),ids,List.of(),true,businessSourceId));
    }
}
