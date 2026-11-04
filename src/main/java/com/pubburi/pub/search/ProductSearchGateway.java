package com.pubburi.pub.search;

import java.util.List;
import java.util.Optional;
import com.pubburi.pub.controller.api.PageCriteria;
import com.pubburi.pub.controller.api.ProductSearchFilters;

public interface ProductSearchGateway {
    boolean enabled();
    Optional<Selection> search(String sourceId, String type, String q, String sort, ProductSearchFilters filters, PageCriteria page);
    record Selection(List<Integer> ids, int total) { }
    static ProductSearchGateway disabled() {
        return new ProductSearchGateway() {
            @Override public boolean enabled() { return false; }
            @Override public Optional<Selection> search(String sourceId,String type,String q,String sort,ProductSearchFilters filters,PageCriteria page) { return Optional.empty(); }
        };
    }
}
