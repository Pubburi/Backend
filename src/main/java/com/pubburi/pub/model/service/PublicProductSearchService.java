package com.pubburi.pub.model.service;

import org.springframework.stereotype.Service;
import com.pubburi.pub.controller.api.PageCriteria;
import com.pubburi.pub.controller.api.PageResponse;
import com.pubburi.pub.controller.api.ProductSearchFilters;
import com.pubburi.pub.model.dao.ProductSearchIdentityDao;
import com.pubburi.pub.model.dto.Product;
import com.pubburi.pub.search.ProductSearchGateway;

@Service
public class PublicProductSearchService {
    public enum Source { DATABASE, ENGINE_VERIFIED, DATABASE_FALLBACK }
    public record Result(PageResponse<Product> page, Source source) { }
    private final ProductService products;
    private final ProductSearchIdentityDao identities;
    private final ProductSearchGateway engine;
    public PublicProductSearchService(ProductService products, ProductSearchIdentityDao identities, ProductSearchGateway engine) {
        this.products=products;this.identities=identities;this.engine=engine;
    }
    // Remote I/O is outside the canonical service's short read-only RR transaction.
    public Result search(String type,String q,String sort,ProductSearchFilters filters,PageCriteria page) {
        long offset=((long)page.page()-1)*page.size();
        if(offset<0 || offset>Integer.MAX_VALUE)throw new IllegalArgumentException("Product page offset exceeds the supported range");
        if(!engine.enabled())return new Result(products.searchProducts(type,q,sort,filters,page),Source.DATABASE);
        // Real database failures propagate. An unavailable engine must never hide one.
        String sourceId=identities.selectSourceId();
        var selected=engine.search(sourceId,clean(type),clean(q),clean(sort),filters,page);
        var current=products.searchProducts(type,q,sort,filters,page);
        if(selected.isPresent() && selected.get().total()==current.total()
                && selected.get().ids().equals(current.items().stream().map(Product::getId).toList()))
            return new Result(current,Source.ENGINE_VERIFIED);
        return new Result(current,Source.DATABASE_FALLBACK);
    }
    private String clean(String value){return value==null || value.trim().isEmpty()?null:value.trim();}
}
