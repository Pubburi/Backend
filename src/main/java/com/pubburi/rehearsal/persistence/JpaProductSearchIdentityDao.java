package com.pubburi.rehearsal.persistence;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pubburi.pub.model.dao.ProductSearchIdentityDao;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.TreeMap;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Transactional(readOnly = true)
public class JpaProductSearchIdentityDao implements ProductSearchIdentityDao {
    private static final ObjectMapper IDENTITY_JSON = new ObjectMapper()
        .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final List<String> IDENTITY_KEYS = List.of("source_id", "cluster_uuid", "index_uuid", "index_name", "schema", "data_kind");
    @PersistenceContext private EntityManager entityManager;
    @Override public String selectSourceId() {
        List<?> values = entityManager.createNativeQuery("SELECT source_id::text FROM pubburi.t_product_search_relay_target WHERE singleton_id=1").getResultList();
        return values.isEmpty() ? null : (String) values.get(0);
    }

    @Override public BoundTarget selectBoundTarget() {
        List<?> values = entityManager.createNativeQuery("""
            SELECT source_id::text, fingerprint,
                CASE WHEN octet_length(identity_json::text) <= 2048 THEN identity_json::text END
            FROM pubburi.t_product_search_relay_target WHERE singleton_id=1
            """).getResultList();
        if (values.size() != 1) return null;
        Object[] row = (Object[]) values.get(0);
        if (!(row[2] instanceof String raw)) return null;
        try {
            var json = IDENTITY_JSON.readTree(raw);
            if (!json.isObject() || json.size() != IDENTITY_KEYS.size()) return null;
            var identity = new TreeMap<String, String>();
            for (String key : IDENTITY_KEYS) {
                if (!json.path(key).isTextual()) return null;
                identity.put(key, json.path(key).textValue());
            }
            String fingerprint = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(IDENTITY_JSON.writeValueAsBytes(identity)));
            if (!fingerprint.equals(row[1]) || !identity.get("source_id").equals(row[0])
                    || !"products-v1".equals(identity.get("schema")) || !"backend_product_events".equals(identity.get("data_kind"))
                    || !identity.get("cluster_uuid").matches("[A-Za-z0-9_-]{1,128}")) return null;
            return new BoundTarget(identity.get("source_id"), identity.get("index_name"), identity.get("index_uuid"), fingerprint);
        } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
            return null;
        } catch (java.security.NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is required for search identity", unavailable);
        }
    }
}
