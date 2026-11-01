-- Manual 007: apply only with all business writers stopped, after 001..006.
-- Back up/validate first. DDL commits implicitly; do not use --force or rerun blindly.
-- Version state intentionally survives product deletion; no FK to t_product.
CREATE TABLE t_product_search_version (
    product_id INT PRIMARY KEY,
    source_version BIGINT NOT NULL,
    CONSTRAINT ck_search_version_product CHECK (product_id > 0),
    CONSTRAINT ck_search_version_nonnegative CHECK (source_version >= 0)
) ENGINE=InnoDB;

CREATE TABLE t_product_search_outbox (
    event_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    product_id INT NOT NULL,
    source_version BIGINT NOT NULL,
    deleted BOOLEAN NOT NULL,
    payload JSON NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_search_product_version (product_id, source_version),
    CONSTRAINT ck_search_outbox_version CHECK (source_version > 0),
    CONSTRAINT ck_search_outbox_deleted CHECK (deleted IN (0, 1)),
    CONSTRAINT fk_search_outbox_version FOREIGN KEY (product_id)
        REFERENCES t_product_search_version(product_id) ON DELETE RESTRICT
) ENGINE=InnoDB;

-- Current state only: this does not reconstruct pre-migration changes/deletions.
START TRANSACTION;
INSERT INTO t_product_search_version (product_id, source_version)
SELECT id, 1 FROM t_product ORDER BY id;

INSERT INTO t_product_search_outbox (product_id, source_version, deleted, payload)
SELECT p.id, 1, FALSE,
    JSON_OBJECT('productId', p.id, 'sourceVersion', 1, 'deleted', CAST('false' AS JSON),
        'document', JSON_OBJECT('name', p.name, 'type', p.type, 'priceKrw', p.price,
            'abvPercent', p.abv, 'orderCount', p.order_count,
            'tasteTags', CAST(IF(COALESCE(f.taste_known, FALSE),
                COALESCE((SELECT JSON_ARRAYAGG(t.tag) FROM t_product_feature_tag t
                    WHERE t.product_id=p.id AND t.kind='TASTE'), '[]'), 'null') AS JSON),
            'aromaTags', CAST(IF(COALESCE(f.aroma_known, FALSE),
                COALESCE((SELECT JSON_ARRAYAGG(t.tag) FROM t_product_feature_tag t
                    WHERE t.product_id=p.id AND t.kind='AROMA'), '[]'), 'null') AS JSON)))
FROM t_product p LEFT JOIN t_product_feature f ON f.product_id=p.id ORDER BY p.id;
COMMIT;
