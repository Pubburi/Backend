package com.pubburi.rehearsal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import com.pubburi.pub.controller.auth.SessionUser;
import com.pubburi.pub.model.dao.*;
import com.pubburi.pub.model.dto.*;
import com.pubburi.pub.model.service.OwnedContentSnapshotService;
import jakarta.persistence.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

class OrderContextSnapshotServiceTests {
    final SessionUser owner=NotebookContractTests.OWNER;
    final UserDao users=mock(UserDao.class);final OrderDao orders=mock(OrderDao.class);final ProductDao products=mock(ProductDao.class);
    final OwnedContentSnapshotService contents=mock(OwnedContentSnapshotService.class);final EntityManager em=mock(EntityManager.class);
    @SuppressWarnings("unchecked") final TypedQuery<Long> count=mock(TypedQuery.class);
    final OrderContextSnapshotService service=new OrderContextSnapshotService(users,orders,products,contents);
    OrderContextSnapshotServiceTests(){ReflectionTestUtils.setField(service,"em",em);when(em.createQuery(anyString(),eq(Long.class))).thenReturn(count);when(count.setParameter("id",7)).thenReturn(count);}
    User identity(String id){var value=new User();value.setId(id);value.setAuthEpoch(owner.authEpoch());return value;}
    OrderInfo header(String id){var order=new OrderInfo();order.setoId(7);order.setUserId(id);order.setOrderType("TAKEOUT");return order;}
    @Test void dbCanonicalIdentityAllowsAnOwnedAliasButNotAdminAccessToAnotherAccount(){
        when(users.selectSessionIdentity(owner.id(),owner.authEpoch())).thenReturn(identity(owner.id()));
        when(orders.selectOrderInfo(7)).thenReturn(header("ÓWN"));when(users.selectSessionIdentity("ÓWN",owner.authEpoch())).thenReturn(identity(owner.id()));
        when(count.getSingleResult()).thenReturn(0L);when(orders.getOrderDetailInfo(7)).thenReturn(List.of());
        assertEquals(7,service.read(owner,7,List.of(),null).order().oId());
        when(orders.selectOrderInfo(7)).thenReturn(header("other"));when(users.selectSessionIdentity("other",owner.authEpoch())).thenReturn(identity("other"));
        var admin=new SessionUser(owner.id(),owner.name(),0,"ADMIN",owner.authEpoch());
        assertEquals(HttpStatus.NOT_FOUND,assertThrows(ResponseStatusException.class,()->service.read(admin,7,List.of(),null)).getStatusCode());
        when(users.selectSessionIdentity(owner.id(),owner.authEpoch())).thenReturn(null);
        assertEquals(HttpStatus.UNAUTHORIZED,assertThrows(ResponseStatusException.class,()->service.read(owner,7,List.of(),null)).getStatusCode());
    }
    @Test void moreThanTwentyRowsUsesOnlyTheExactCountAndDoesNotLoadOrGuessAPartialOrder(){
        when(users.selectSessionIdentity(owner.id(),owner.authEpoch())).thenReturn(identity(owner.id()));when(orders.selectOrderInfo(7)).thenReturn(header(owner.id()));when(count.getSingleResult()).thenReturn(21L);
        var value=service.read(owner,7,List.of(),null);assertEquals(21,value.referenceCount());assertTrue(value.order().details().isEmpty());assertTrue(value.currentProducts().isEmpty());
        verify(orders,never()).getOrderDetailInfo(anyInt());verifyNoInteractions(products);verify(users,never()).selectByIdForUpdate(anyString());verify(orders,never()).insert(any());verify(orders,never()).delete(anyInt());
    }
}
