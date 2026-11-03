package com.pubburi.rehearsal.persistence;

import com.pubburi.pub.model.dto.UserPreferences;
import java.math.BigDecimal;

public record PreferenceHeaderView(String userId, String surveyStatus, boolean categoriesKnown,
        boolean tasteKnown, boolean aromaKnown, BigDecimal targetAbvPercent, Integer minPriceKrw,
        Integer maxPriceKrw, BigDecimal minAbvPercent, BigDecimal maxAbvPercent) {
    UserPreferences toDto() {
        var value = new UserPreferences(); value.setUserId(userId); value.setSurveyStatus(surveyStatus);
        value.setCategoriesKnown(categoriesKnown); value.setTasteKnown(tasteKnown); value.setAromaKnown(aromaKnown);
        value.setTargetAbvPercent(targetAbvPercent); value.setMinPriceKrw(minPriceKrw); value.setMaxPriceKrw(maxPriceKrw);
        value.setMinAbvPercent(minAbvPercent); value.setMaxAbvPercent(maxAbvPercent); return value;
    }
}
