package com.pubburi.pub.model.service;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.CannotCreateTransactionException;
import com.pubburi.pub.controller.api.PageCriteria;
import com.pubburi.pub.controller.api.PageResponse;
import com.pubburi.pub.controller.api.ProductSearchFilters;
import com.pubburi.pub.model.dao.ProductSearchIdentityDao;
import com.pubburi.pub.model.dto.Product;
import com.pubburi.pub.model.dto.ProductSuggestion;
import com.pubburi.pub.search.ProductNamePrefix;
import com.pubburi.pub.search.ProductSearchGateway;
import com.pubburi.pub.search.ProductSearchHint;

@Service
public class PublicProductSearchService {
    private static final Logger LOGGER = LoggerFactory.getLogger(PublicProductSearchService.class);
    private static final PageCriteria HINT_PAGE = new PageCriteria(1, 1);
    public enum Source { DATABASE, ENGINE_VERIFIED, DATABASE_FALLBACK }
    public record Result(PageResponse<Product> page, Source source, ProductSearchHint hint) { }
    public record Suggestions(List<ProductSuggestion> items, Source source) { }
    private final ProductService products;
    private final ProductSearchIdentityDao identities;
    private final ProductSearchGateway engine;
    public PublicProductSearchService(ProductService products, ProductSearchIdentityDao identities, ProductSearchGateway engine) {
        this.products=products;this.identities=identities;this.engine=engine;
    }
    public Suggestions suggest(String query) {
        String prefix=ProductNamePrefix.normalize(query);
        if(prefix.isEmpty())return new Suggestions(List.of(),Source.DATABASE);
        if(!engine.enabled())return new Suggestions(products.suggestByName(prefix),Source.DATABASE);
        var selected=engine.suggest(identities.selectSourceId(),prefix);
        // Read current canonical facts only after remote I/O; source documents never enter the response.
        var current=products.suggestByName(prefix);
        boolean verified=selected.isPresent() && selected.get().ids().equals(current.stream().map(ProductSuggestion::id).toList());
        return new Suggestions(current,verified?Source.ENGINE_VERIFIED:Source.DATABASE_FALLBACK);
    }
    // Remote I/O is outside the canonical service's short read-only RR transaction.
    public Result search(String type,String q,String sort,ProductSearchFilters filters,PageCriteria page) {
        long offset=((long)page.page()-1)*page.size();
        if(offset<0 || offset>Integer.MAX_VALUE)throw new IllegalArgumentException("Product page offset exceeds the supported range");
        if (!engine.enabled()) {
            var current = products.searchProducts(type, q, sort, filters, page);
            return withHint(current, Source.DATABASE, type, q, sort, filters);
        }
        // Real database failures propagate. An unavailable engine must never hide one.
        String sourceId=identities.selectSourceId();
        var selected=engine.search(sourceId,clean(type),clean(q),clean(sort),filters,page);
        var current=products.searchProducts(type,q,sort,filters,page);
        boolean verified = selected.isPresent() && selected.get().total() == current.total()
            && selected.get().ids().equals(current.items().stream().map(Product::getId).toList());
        return withHint(current, verified ? Source.ENGINE_VERIFIED : Source.DATABASE_FALLBACK, type, q, sort, filters);
    }

    private Result withHint(PageResponse<Product> current, Source source, String type, String q, String sort,
            ProductSearchFilters filters) {
        var candidate = current.total() == 0 ? ProductSearchHint.forQuery(q) : null;
        if (candidate != null) {
            try {
                if (products.searchProducts(type, candidate.query(), sort, filters, HINT_PAGE).items().isEmpty()) candidate = null;
            } catch (DataAccessException | CannotCreateTransactionException unavailable) {
                LOGGER.warn("Optional search hint verification failed ({})", unavailable.getClass().getSimpleName());
                candidate = null;
            }
        }
        return new Result(current, source, candidate);
    }
    private String clean(String value){return value==null || value.trim().isEmpty()?null:value.trim();}
}
