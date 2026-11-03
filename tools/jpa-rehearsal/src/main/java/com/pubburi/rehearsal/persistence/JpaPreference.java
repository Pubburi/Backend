package com.pubburi.rehearsal.persistence;

import com.pubburi.pub.model.dto.UserPreferences;
import jakarta.persistence.*;
import java.math.BigDecimal;
import org.springframework.data.domain.Persistable;

@Entity(name = "RehearsalPreference")
@Table(name = "t_user_preference", schema = "pubburi")
public class JpaPreference implements Persistable<String> {
    @Id @Column(name = "user_id", length = 100) String userId;
    @Column(name = "survey_status", length = 11, nullable = false) String surveyStatus;
    @Column(name = "categories_known", nullable = false) boolean categoriesKnown;
    @Column(name = "taste_known", nullable = false) boolean tasteKnown;
    @Column(name = "aroma_known", nullable = false) boolean aromaKnown;
    @Column(name = "target_abv_percent", precision = 5, scale = 2) BigDecimal targetAbvPercent;
    @Column(name = "min_price_krw") Integer minPriceKrw;
    @Column(name = "max_price_krw") Integer maxPriceKrw;
    @Column(name = "min_abv_percent", precision = 5, scale = 2) BigDecimal minAbvPercent;
    @Column(name = "max_abv_percent", precision = 5, scale = 2) BigDecimal maxAbvPercent;
    @Transient private boolean fresh = true;
    protected JpaPreference() { }
    static JpaPreference from(UserPreferences value) { var row = new JpaPreference(); row.userId = value.getUserId(); row.apply(value); return row; }
    void apply(UserPreferences value) {
        surveyStatus = value.getSurveyStatus(); categoriesKnown = value.isCategoriesKnown();
        tasteKnown = value.isTasteKnown(); aromaKnown = value.isAromaKnown();
        targetAbvPercent = value.getTargetAbvPercent(); minPriceKrw = value.getMinPriceKrw(); maxPriceKrw = value.getMaxPriceKrw();
        minAbvPercent = value.getMinAbvPercent(); maxAbvPercent = value.getMaxAbvPercent();
    }
    @Override public String getId() { return userId; }
    @Override public boolean isNew() { return fresh; }
    @PostLoad @PostPersist void persisted() { fresh = false; }
}
