-- MySQL 8.4, after 001/002/003. Back up and stop application writes first.
-- New tables only: no inferred taste/aroma and no changes to products/orders/users.
-- One-time DDL, not an idempotent migration. Do not run with mysql --force.
-- If only the first CREATE succeeds, inspect/verify it before applying the second.
CREATE TABLE t_product_feature (
    product_id INT PRIMARY KEY,
    volume_ml INT NULL,
    description VARCHAR(2000) NULL,
    taste_known BOOLEAN NOT NULL DEFAULT FALSE,
    aroma_known BOOLEAN NOT NULL DEFAULT FALSE,
    source_name VARCHAR(200) NULL,
    source_url VARCHAR(1000) NULL,
    source_note VARCHAR(1000) NULL,
    CONSTRAINT ck_feature_volume CHECK (volume_ml IS NULL OR volume_ml BETWEEN 1 AND 1000000),
    CONSTRAINT ck_feature_taste_known CHECK (taste_known IN (0, 1)),
    CONSTRAINT ck_feature_aroma_known CHECK (aroma_known IN (0, 1)),
    CONSTRAINT ck_feature_source CHECK (
        (volume_ml IS NULL AND description IS NULL AND taste_known = FALSE AND aroma_known = FALSE)
        OR (source_name IS NOT NULL AND CHAR_LENGTH(TRIM(source_name)) > 0)
    ),
    CONSTRAINT fk_product_feature_product FOREIGN KEY (product_id)
        REFERENCES t_product(id) ON DELETE CASCADE
);

CREATE TABLE t_product_feature_tag (
    product_id INT NOT NULL,
    kind VARCHAR(5) NOT NULL,
    tag VARCHAR(40) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    PRIMARY KEY (product_id, kind, tag),
    CONSTRAINT ck_feature_tag_kind CHECK (kind IN ('TASTE', 'AROMA')),
    CONSTRAINT ck_feature_tag_nonblank CHECK (CHAR_LENGTH(TRIM(tag)) > 0),
    CONSTRAINT fk_product_feature_tag FOREIGN KEY (product_id)
        REFERENCES t_product_feature(product_id) ON DELETE CASCADE
);
