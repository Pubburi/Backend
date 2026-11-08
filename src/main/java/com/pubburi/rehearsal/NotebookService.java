package com.pubburi.rehearsal;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.pubburi.pub.controller.api.*;
import com.pubburi.pub.controller.auth.SessionUser;
import com.pubburi.pub.controller.response.*;
import com.pubburi.pub.model.dao.*;
import com.pubburi.rehearsal.persistence.*;
import com.pubburi.rehearsal.persistence.JpaNotebook.Experience;
import org.springframework.data.domain.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;
import java.util.*;

@Service
public class NotebookService {
    private final UserDao users;private final ProductDao products;private final NotebookRepository notes;
    public NotebookService(UserDao users,ProductDao products,NotebookRepository notes){this.users=users;this.products=products;this.notes=notes;}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Entry(UUID id,
        @io.swagger.v3.oas.annotations.media.Schema(nullable=true) Integer productId,String productName,
        @io.swagger.v3.oas.annotations.media.Schema(nullable=true) ProductResponse currentProduct,Experience experience,
        @io.swagger.v3.oas.annotations.media.Schema(nullable=true) BigDecimal rating,
        @io.swagger.v3.oas.annotations.media.Schema(nullable=true) List<String> tasteTags,
        @io.swagger.v3.oas.annotations.media.Schema(nullable=true) List<String> aromaTags,
        @io.swagger.v3.oas.annotations.media.Schema(nullable=true) String memo,long version,long createdAt,long updatedAt){ }
    private void current(SessionUser owner){if(users.selectSessionIdentity(owner.id(),owner.authEpoch())==null)throw expired();}
    private void lockOwner(SessionUser owner){var user=users.selectByIdForUpdate(owner.id());if(user==null || !owner.authEpoch().equals(user.getAuthEpoch()))throw expired();}
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public PageResponse<Entry> page(SessionUser owner,PageCriteria criteria,Experience experience){
        current(owner);var page=notes.page(owner.id(),experience,PageRequest.of(criteria.page()-1,criteria.size(),Sort.by(Sort.Order.desc("updatedAt"),Sort.Order.asc("id"))));
        return PageResponse.of(entries(page.getContent()),criteria,Math.toIntExact(page.getTotalElements()));
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Entry get(SessionUser owner,UUID id){current(owner);return entry(notes.owned(owner.id(),id).orElseThrow(NotebookService::missing));}
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Entry product(SessionUser owner,int productId){current(owner);return notes.product(owner.id(),productId).map(this::entry).orElse(null);}
    @Transactional
    public Entry create(SessionUser owner,NotebookInput.Create input){
        lockOwner(owner);
        if(notes.product(owner.id(),input.productId()).isPresent())throw conflict("A notebook entry already exists for this product");
        var product=products.selectByIdForUpdate(input.productId());if(product==null)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Product not found");
        var content=input.content();var row=JpaNotebook.create(owner.id(),product.getId(),product.getName(),content.experience(),content.rating(),content.tasteTags(),content.aromaTags(),content.memo());
        return entry(notes.saveAndFlush(row));
    }
    @Transactional
    public Entry change(SessionUser owner,UUID id,NotebookInput.Change input){
        lockOwner(owner);var row=notes.locked(owner.id(),id).orElseThrow(NotebookService::missing);version(row,input.expectedVersion());
        if(row.version()==NotebookInput.MAX_VERSION)throw conflict("Notebook revision limit reached");
        var content=input.content();row.change(content.experience(),content.rating(),content.tasteTags(),content.aromaTags(),content.memo());
        return entry(notes.saveAndFlush(row));
    }
    @Transactional
    public void delete(SessionUser owner,UUID id,long expectedVersion){
        lockOwner(owner);var row=notes.locked(owner.id(),id).orElseThrow(NotebookService::missing);version(row,expectedVersion);notes.delete(row);notes.flush();
    }
    private void version(JpaNotebook row,long expected){if(row.version()!=expected)throw conflict("Notebook entry changed; reload before saving");}
    private List<Entry> entries(List<JpaNotebook> rows){
        var ids=rows.stream().map(JpaNotebook::productId).filter(Objects::nonNull).distinct().toList();var current=new HashMap<Integer,ProductResponse>();
        if(!ids.isEmpty())for(var product:products.selectCurrentByIds(ids))current.put(product.getId(),ResponseMapper.product(product));
        return rows.stream().map(row->new Entry(row.id(),row.productId(),row.productName(),row.productId()==null?null:current.get(row.productId()),
            row.experience(),row.rating(),row.tasteTags(),row.aromaTags(),row.memo(),row.version(),row.createdAt(),row.updatedAt())).toList();
    }
    private Entry entry(JpaNotebook row){return entries(List.of(row)).get(0);}
    private static ResponseStatusException expired(){return new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Account session is no longer current");}
    private static ResponseStatusException missing(){return new ResponseStatusException(HttpStatus.NOT_FOUND,"Notebook entry not found");}
    private static ResponseStatusException conflict(String reason){return new ResponseStatusException(HttpStatus.CONFLICT,reason);}
}
