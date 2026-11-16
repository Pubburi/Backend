-- MySQL 8.4 after 001..005. Back up, stop writes, verify t_user.id/database collation match.
-- New empty table only; no fabricated user interest or changes to existing data.
-- One-time DDL. Do not run with --force or reset an existing DB with data.sql.
CREATE TABLE t_user_favorite (
    user_id VARCHAR(100) NOT NULL,
    product_id INT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (user_id, product_id),
    INDEX idx_favorite_user_created (user_id, created_at, product_id),
    INDEX idx_favorite_product (product_id),
    CONSTRAINT fk_favorite_user FOREIGN KEY (user_id) REFERENCES t_user(id) ON DELETE CASCADE,
    CONSTRAINT fk_favorite_product FOREIGN KEY (product_id) REFERENCES t_product(id) ON DELETE CASCADE
);
