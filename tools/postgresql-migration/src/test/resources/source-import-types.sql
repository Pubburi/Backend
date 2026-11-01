-- Only the new copied typed fixture database, before its QUIESCED export window is published.
-- All values below are authored type/retention examples, never product facts or real user preference.
SET time_zone='+00:00';
SET @fixture_user=(SELECT id FROM t_user ORDER BY id LIMIT 1);
SET @fixture_product=(SELECT id FROM t_product ORDER BY id LIMIT 1);
INSERT INTO t_product_feature(product_id,volume_ml,description,taste_known,aroma_known,source_name,source_note)
VALUES(@fixture_product,500,'Authored import type fixture',0,1,'Authored verification only','Unknown taste / inspected aroma');
INSERT INTO t_product_feature_tag(product_id,kind,tag) VALUES(@fixture_product,'AROMA','검수향');
INSERT INTO t_user_preference(user_id,survey_status,categories_known,taste_known,aroma_known,target_abv_percent,min_price_krw,max_price_krw,min_abv_percent,max_abv_percent)
VALUES(@fixture_user,'COMPLETED',1,0,1,15.25,0,20000,0.00,10.00);
INSERT INTO t_user_preference_value(user_id,kind,value_text) VALUES(@fixture_user,'CATEGORY','맥주'),(@fixture_user,'AROMA','검수향');
INSERT INTO t_user_favorite(user_id,product_id,created_at) VALUES(@fixture_user,@fixture_product,'2026-11-01 18:00:00.123456');
INSERT INTO t_order(user_id,order_type,order_table,order_time,completed,total_price,price_basis)
VALUES(NULL,'DEMO',NULL,'2026-11-01 18:00:00','N',15000,'SNAPSHOT');
SET @fixture_order=LAST_INSERT_ID();
INSERT INTO t_order_detail(order_id,product_id,quantity,product_name,product_type,product_img,unit_price,sum_price)
VALUES(@fixture_order,NULL,2,'Authored deleted product','맥주','authored.png',7500,15000);
SET @fixture_event=(SELECT MIN(event_id) FROM t_product_search_outbox);
UPDATE t_product_search_delivery SET status='IN_FLIGHT',claim_token='00000000-0000-4000-8000-000000000019',
lease_until='2026-11-01 18:01:00.123456',attempts=2,last_error='authored_transport_failure' WHERE event_id=@fixture_event;
UPDATE t_product_search_delivery SET status='DELIVERED',delivered_at='2026-11-01 18:00:00.654321',attempts=1
WHERE event_id=(SELECT MIN(event_id) FROM t_product_search_outbox WHERE event_id>@fixture_event);
UPDATE t_product_search_relay_target SET fingerprint=REPEAT('a',64),
identity_json=JSON_OBJECT('source_id',source_id,'cluster_uuid','authored-cluster','index_uuid','authored-index','index_name','pubburi-products-v1-authored','schema','products-v1','data_kind','backend_product_events'),
bound_at='2026-11-01 18:00:00.123456' WHERE singleton_id=1;
