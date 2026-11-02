package com.pubburi.rehearsal.persistence;

import com.pubburi.pub.controller.api.*;
import com.pubburi.pub.model.dto.Product;
import com.pubburi.pub.model.dto.ProductFeatures;
import com.pubburi.pub.model.dto.ProductFeatureTag;
import jakarta.persistence.*;
import jakarta.persistence.criteria.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Actual PG queries only. No ProductDao write stub or unused outbox bean is registered. */
@Service
@Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
public class JpaProductQueries {
    @PersistenceContext private EntityManager entityManager;

    public PageResponse<Product> page(String type,String q,String sort,ProductSearchFilters filters,PageCriteria criteria) {
        long offset=((long)criteria.page()-1)*criteria.size();
        if(offset<0 || offset>Integer.MAX_VALUE)
            throw new IllegalArgumentException("Product page offset exceeds the supported range");
        String safeType=blankToNull(type),safeQ=blankToNull(q),safeSort=blankToNull(sort);
        var cb=entityManager.getCriteriaBuilder();
        var query=cb.createQuery(Product.class);var product=query.from(JpaProduct.class);
        query.select(projection(cb,product)).where(predicates(cb,query,product,safeType,safeQ,filters));
        query.orderBy(order(cb,product,safeSort));
        var items=entityManager.createQuery(query).setFirstResult((int)offset).setMaxResults(criteria.size()).getResultList();
        var countQuery=cb.createQuery(Long.class);var counted=countQuery.from(JpaProduct.class);
        countQuery.select(cb.count(counted)).where(predicates(cb,countQuery,counted,safeType,safeQ,filters));
        int count=Math.toIntExact(entityManager.createQuery(countQuery).getSingleResult());
        return PageResponse.of(items,criteria,count);
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

    public ProductFeatures features(int id) {
        if(id<=0)return null;
        var rows=entityManager.createQuery("""
            select new com.pubburi.rehearsal.persistence.ProductFeatureView(p.id,f.volumeMl,f.description,
              coalesce(f.tasteKnown,false),coalesce(f.aromaKnown,false),f.sourceName,f.sourceUrl,f.sourceNote)
            from RehearsalProduct p left join RehearsalProductFeature f on f.productId=p.id where p.id=:id
            """,ProductFeatureView.class).setParameter("id",id).getResultList();
        if(rows.isEmpty())return null;
        ProductFeatures value=rows.get(0).dto();
        var tags=entityManager.createQuery("""
            select new com.pubburi.pub.model.dto.ProductFeatureTag(t.kind,t.tag)
            from RehearsalProductTag t where t.productId=:id
            """,ProductFeatureTag.class).setParameter("id",id).getResultList();
        value.setTasteTags(value.isTasteKnown()?tagsOf(tags,"TASTE"):null);
        value.setAromaTags(value.isAromaKnown()?tagsOf(tags,"AROMA"):null);
        return value;
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

    private static List<String> tagsOf(List<ProductFeatureTag> tags,String kind) {
        return tags.stream().filter(t->kind.equals(t.kind())).map(ProductFeatureTag::tag).sorted().toList();
    }
    private static String blankToNull(String value) {return value==null || value.trim().isEmpty()?null:value.trim();}
}
