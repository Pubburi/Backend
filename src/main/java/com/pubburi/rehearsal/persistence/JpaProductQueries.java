package com.pubburi.rehearsal.persistence;

import com.pubburi.pub.controller.api.*;
import com.pubburi.pub.model.dto.Product;
import com.pubburi.pub.model.dto.ProductSuggestion;
import com.pubburi.pub.model.dto.ProductFeatures;
import com.pubburi.pub.model.dto.ProductFeatureTag;
import com.pubburi.pub.model.dto.ProductFeatureTagRow;
import jakarta.persistence.*;
import jakarta.persistence.criteria.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Existing DTO projections reused by the actual JPA DAO adapters. */
@Service
@Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
public class JpaProductQueries {
    @PersistenceContext private EntityManager entityManager;

    public List<Product> filtered(String type,String q,String sort,ProductSearchFilters filters,int limit,int offset) {
        if(limit<0 || offset<0)throw new IllegalArgumentException("Product page bounds must be nonnegative");
        String safeType=blankToNull(type),safeQ=blankToNull(q),safeSort=blankToNull(sort);
        var cb=entityManager.getCriteriaBuilder();
        var query=cb.createQuery(Product.class);var product=query.from(JpaProduct.class);
        query.select(projection(cb,product)).where(predicates(cb,query,product,safeType,safeQ,filters));
        query.orderBy(order(cb,product,safeSort));
        return entityManager.createQuery(query).setFirstResult(offset).setMaxResults(limit).getResultList();
    }

    public int count(String type,String q,ProductSearchFilters filters) {
        var cb=entityManager.getCriteriaBuilder();
        var countQuery=cb.createQuery(Long.class);var counted=countQuery.from(JpaProduct.class);
        countQuery.select(cb.count(counted)).where(predicates(cb,countQuery,counted,blankToNull(type),blankToNull(q),filters));
        return Math.toIntExact(entityManager.createQuery(countQuery).getSingleResult());
    }

    public List<Product> byIdOrder(int limit) {
        var cb=entityManager.getCriteriaBuilder();var query=cb.createQuery(Product.class);var product=query.from(JpaProduct.class);
        query.select(projection(cb,product)).orderBy(cb.asc(product.get("id")));
        return entityManager.createQuery(query).setMaxResults(limit).getResultList();
    }

    public List<Product> currentByIds(List<Integer> ids) {
        if(ids.isEmpty())return List.of();
        var cb=entityManager.getCriteriaBuilder();var query=cb.createQuery(Product.class);var product=query.from(JpaProduct.class);
        query.select(projection(cb,product)).where(product.get("id").in(ids));
        return entityManager.createQuery(query).getResultList();
    }

    public List<ProductSuggestion> nameSuggestions(String pattern, int limit) {
        var cb = entityManager.getCriteriaBuilder();
        var query = cb.createQuery(ProductSuggestion.class);
        var product = query.from(JpaProduct.class);
        Path<String> name = product.get("name");
        var prefix = cb.parameter(String.class, "prefix");
        query.select(cb.construct(ProductSuggestion.class, product.get("id"), name, product.get("type"), product.get("price")))
            .where(cb.like(name, prefix, '!')).orderBy(cb.asc(name), cb.asc(product.get("id")));
        return entityManager.createQuery(query).setParameter(prefix, pattern).setMaxResults(limit).getResultList();
    }

    public Product exactName(String name) {
        var cb=entityManager.getCriteriaBuilder();var query=cb.createQuery(Product.class);var product=query.from(JpaProduct.class);
        query.select(projection(cb,product)).where(cb.equal(product.get("name"),name));
        return entityManager.createQuery(query).setMaxResults(1).getResultList().stream().findFirst().orElse(null);
    }

    public List<Product> topByTypeExcludingName(String type,String name,int limit) {
        var cb=entityManager.getCriteriaBuilder();var query=cb.createQuery(Product.class);var product=query.from(JpaProduct.class);
        query.select(projection(cb,product)).where(cb.equal(product.get("type"),type),cb.notEqual(product.get("name"),name)).orderBy(order(cb,product,"popular"));
        return entityManager.createQuery(query).setMaxResults(limit).getResultList();
    }

    public List<Product> popular() {
        var cb=entityManager.getCriteriaBuilder();var query=cb.createQuery(Product.class);var product=query.from(JpaProduct.class);
        query.select(projection(cb,product)).orderBy(order(cb,product,"popular"));
        return entityManager.createQuery(query).setMaxResults(12).getResultList();
    }

    public Product find(int id) {
        if(id<=0)return null;
        var cb=entityManager.getCriteriaBuilder();var query=cb.createQuery(Product.class);var product=query.from(JpaProduct.class);
        query.select(projection(cb,product)).where(cb.equal(product.get("id"),id));
        return entityManager.createQuery(query).getResultList().stream().findFirst().orElse(null);
    }

    public ProductFeatures featureHeader(int id) {
        if(id<=0)return null;
        return featureHeaders(List.of(id)).stream().findFirst().orElse(null);
    }

    public List<ProductFeatures> featureHeaders(List<Integer> ids) {
        if(ids.isEmpty())return List.of();
        var rows=entityManager.createQuery("""
            select new com.pubburi.rehearsal.persistence.ProductFeatureView(p.id,f.volumeMl,f.description,
              coalesce(f.tasteKnown,false),coalesce(f.aromaKnown,false),f.sourceName,f.sourceUrl,f.sourceNote)
            from RehearsalProduct p left join RehearsalProductFeature f on f.productId=p.id where p.id in :ids
            """,ProductFeatureView.class).setParameter("ids",ids).getResultList();
        return rows.stream().map(ProductFeatureView::dto).toList();
    }

    public List<ProductFeatureTagRow> tagsByProductIds(List<Integer> ids) {
        if(ids.isEmpty())return List.of();
        return entityManager.createQuery("""
            select new com.pubburi.pub.model.dto.ProductFeatureTagRow(t.productId,t.kind,t.tag)
            from RehearsalProductTag t where t.productId in :ids
            """,ProductFeatureTagRow.class).setParameter("ids",ids).getResultList();
    }

    public List<ProductFeatureTag> tags(int id) {
        return entityManager.createQuery("""
            select new com.pubburi.pub.model.dto.ProductFeatureTag(t.kind,t.tag)
            from RehearsalProductTag t where t.productId=:id
            """,ProductFeatureTag.class).setParameter("id",id).getResultList();
    }

    private static Selection<Product> projection(CriteriaBuilder cb,Root<JpaProduct> p) {
        return cb.construct(Product.class,p.get("id"),p.get("name"),p.get("type"),p.get("price"),p.get("img"),p.get("abv"),p.get("orderCount"));
    }

    private static Predicate[] predicates(CriteriaBuilder cb,CriteriaQuery<?> query,Root<JpaProduct> p,
            String type,String q,ProductSearchFilters f) {
        var all=new ArrayList<Predicate>();
        if(type!=null)all.add(cb.equal(p.get("type"),type));
        if(q!=null) {
            String pattern="%"+q+"%";
            all.add(cb.or(cb.like(p.get("name"),pattern,'\\'),cb.like(p.get("type"),pattern,'\\')));
        }
        if(f.minPriceKrw()!=null)all.add(cb.ge(p.get("price"),f.minPriceKrw()));
        if(f.maxPriceKrw()!=null)all.add(cb.le(p.get("price"),f.maxPriceKrw()));
        // Bind in the original FLOAT/REAL storage domain; never round/cast the product column.
        if(f.minAbvPercent()!=null)all.add(cb.ge(p.get("abv"),f.minAbvPercent().floatValue()));
        if(f.maxAbvPercent()!=null)all.add(cb.le(p.get("abv"),f.maxAbvPercent().floatValue()));
        tagPredicates(cb,query,p,all,"TASTE","tasteKnown",f.tasteTags());
        tagPredicates(cb,query,p,all,"AROMA","aromaKnown",f.aromaTags());
        return all.toArray(Predicate[]::new);
    }

    private static void tagPredicates(CriteriaBuilder cb,CriteriaQuery<?> query,Root<JpaProduct> p,
            List<Predicate> all,String kind,String known,List<String> tags) {
        if(tags.isEmpty())return;
        var feature=query.subquery(Integer.class);var f=feature.from(JpaProductFeature.class);
        feature.select(cb.literal(1)).where(cb.equal(f.get("productId"),p.get("id")),cb.isTrue(f.get(known)));
        all.add(cb.exists(feature));
        for(String value:tags) {
            var tag=query.subquery(Integer.class);var t=tag.from(JpaProductTag.class);
            tag.select(cb.literal(1)).where(cb.equal(t.get("productId"),p.get("id")),cb.equal(t.get("kind"),kind),cb.equal(t.get("tag"),value));
            all.add(cb.exists(tag));
        }
    }

    private static List<Order> order(CriteriaBuilder cb,Root<JpaProduct> p,String sort) {
        Order first=switch(sort==null?"":sort) {
            case "priceAsc" -> cb.asc(p.get("price"));
            case "priceDesc" -> cb.desc(p.get("price"));
            case "name" -> cb.asc(p.get("name"));
            default -> cb.desc(p.get("orderCount"));
        };
        return List.of(first,cb.asc(p.get("id")));
    }

    private static String blankToNull(String value) {return value==null || value.trim().isEmpty()?null:value.trim();}
}
