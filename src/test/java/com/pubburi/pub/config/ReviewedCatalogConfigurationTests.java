package com.pubburi.pub.config;

import static org.junit.jupiter.api.Assertions.*;
import com.pubburi.pub.recommendation.ReviewedLocalFixtures;
import org.junit.jupiter.api.Test;

class ReviewedCatalogConfigurationTests {
    @Test void separateSettingsNeverTurnAnOldDemoFlagOrPartialNewInputIntoLocalAuthority(){
        var config=new RecommendationConfig();
        assertTrue(config.contentCatalogSource("legacy.json","a".repeat(64),false,"authority.json","b".repeat(64),"mapping.json","c".repeat(64),ReviewedLocalFixtures.JSON).current().isEmpty());
        assertTrue(config.contentCatalogSource("","",true,"authority.json","b".repeat(64),"mapping.json","c".repeat(64),ReviewedLocalFixtures.JSON).current().isEmpty());
        assertTrue(config.contentCatalogSource("","",false,"authority.json","b".repeat(64),"","",ReviewedLocalFixtures.JSON).current().isEmpty());
    }
}
