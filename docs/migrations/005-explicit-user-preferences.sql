-- MySQL 8.4 after 001/002/003/004. Back up and stop all application writes.
-- Check that t_user.id and database defaults use the same character set/collation.
-- New empty tables only. One-time DDL; no --force, automatic rollback or data.sql reset.
CREATE TABLE t_user_preference (
    user_id VARCHAR(100) PRIMARY KEY,
    survey_status VARCHAR(11) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    categories_known BOOLEAN NOT NULL DEFAULT FALSE,
    taste_known BOOLEAN NOT NULL DEFAULT FALSE,
    aroma_known BOOLEAN NOT NULL DEFAULT FALSE,
    target_abv_percent DECIMAL(5,2) NULL,
    min_price_krw INT NULL,
    max_price_krw INT NULL,
    min_abv_percent DECIMAL(5,2) NULL,
    max_abv_percent DECIMAL(5,2) NULL,
    CONSTRAINT ck_preference_status CHECK (survey_status IN ('NOT_STARTED', 'SKIPPED', 'COMPLETED')),
    CONSTRAINT ck_preference_known CHECK (categories_known IN (0,1) AND taste_known IN (0,1) AND aroma_known IN (0,1)),
    CONSTRAINT ck_preference_target CHECK (target_abv_percent IS NULL OR target_abv_percent BETWEEN 0 AND 100),
    CONSTRAINT ck_preference_price CHECK ((min_price_krw IS NULL OR min_price_krw >= 0)
        AND (max_price_krw IS NULL OR max_price_krw >= 0)
        AND (min_price_krw IS NULL OR max_price_krw IS NULL OR min_price_krw <= max_price_krw)),
    CONSTRAINT ck_preference_abv CHECK ((min_abv_percent IS NULL OR min_abv_percent BETWEEN 0 AND 100)
        AND (max_abv_percent IS NULL OR max_abv_percent BETWEEN 0 AND 100)
        AND (min_abv_percent IS NULL OR max_abv_percent IS NULL OR min_abv_percent <= max_abv_percent)),
    CONSTRAINT ck_preference_skip CHECK (survey_status = 'COMPLETED' OR
        (categories_known = FALSE AND taste_known = FALSE AND aroma_known = FALSE
         AND target_abv_percent IS NULL AND min_price_krw IS NULL AND max_price_krw IS NULL
         AND min_abv_percent IS NULL AND max_abv_percent IS NULL)),
    CONSTRAINT fk_user_preference_user FOREIGN KEY (user_id) REFERENCES t_user(id) ON DELETE CASCADE
);
CREATE TABLE t_user_preference_value (
    user_id VARCHAR(100) NOT NULL,
    kind VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    value_text VARCHAR(40) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    PRIMARY KEY (user_id, kind, value_text),
    CONSTRAINT ck_preference_value_kind CHECK (kind IN ('CATEGORY', 'TASTE', 'AROMA')),
    CONSTRAINT ck_preference_value_nonblank CHECK (CHAR_LENGTH(TRIM(value_text)) > 0),
    CONSTRAINT fk_user_preference_value FOREIGN KEY (user_id) REFERENCES t_user_preference(user_id) ON DELETE CASCADE
);
