package com.pubburi.migration;

import static org.junit.jupiter.api.Assertions.*;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named="PUBBURI_PG_IMPORT_INTEGRATION",matches="1")
class SourceImportTypedIntegrationTests {
    @Test void authoredSourceTypesPrivateStatesAndDeletedReferencesAreCopiedSeparately() throws Exception {
        var helper=new SourceImportIntegrationTests();var env=helper.env("TYPED");SourceImportIntegrationTests.schema(env);
        var source=SourceImportTool.input(env);assertEquals(source.report(),SourceImportTool.run(env));
        var readOnly=new HashMap<>(env);readOnly.remove("PUBBURI_PG_MUTATING");assertEquals(source.report(),SourceImportTool.verify(readOnly));
        try(Connection c=PostgresSchemaTool.Settings.from(env).connect()) {
            SourceImportTool.compare(c,source);
            assertEquals(1,SourceImportIntegrationTests.number(c,"SELECT count(*) FROM pubburi.t_product_feature WHERE volume_ml=500 AND NOT taste_known AND aroma_known"));
            assertEquals(1,SourceImportIntegrationTests.number(c,"SELECT count(*) FROM pubburi.t_product_feature_tag WHERE kind='AROMA' AND tag='검수향'"));
            assertEquals(1,SourceImportIntegrationTests.number(c,"SELECT count(*) FROM pubburi.t_user_preference WHERE target_abv_percent=15.25 AND max_abv_percent=10.00 AND min_abv_percent=0.00 AND min_price_krw=0 AND NOT taste_known"));
            assertEquals(1,SourceImportIntegrationTests.number(c,"SELECT count(*) FROM pubburi.t_user_favorite WHERE to_char(created_at,'US')='123456'"));
            assertEquals(1,SourceImportIntegrationTests.number(c,"SELECT count(*) FROM pubburi.t_order o JOIN pubburi.t_order_detail d ON d.order_id=o.o_id WHERE o.user_id IS NULL AND d.product_id IS NULL AND o.total_price=15000 AND d.unit_price=7500 AND d.sum_price=15000"));
            assertEquals(1,SourceImportIntegrationTests.number(c,"SELECT count(*) FROM pubburi.t_product_search_delivery WHERE status='IN_FLIGHT' AND attempts=2 AND to_char(lease_until,'US')='123456'"));
            assertEquals(1,SourceImportIntegrationTests.number(c,"SELECT count(*) FROM pubburi.t_product_search_delivery WHERE status='DELIVERED' AND to_char(delivered_at,'US')='654321'"));
            assertEquals(1,SourceImportIntegrationTests.number(c,"SELECT count(*) FROM pubburi.t_product_search_relay_target WHERE fingerprint=repeat('a',64) AND identity_json IS NOT NULL AND bound_at IS NOT NULL"));
        }
    }
}
