-- MySQL 8.4, after 001-order-price-snapshot.sql. Run with application writes stopped.
-- Back up and validate on a disposable clone first; do not use mysql --force.
-- These reads fail before ALTER if the BE01 snapshot schema is missing.
SELECT total_price, price_basis FROM t_order LIMIT 0;
SELECT product_name, unit_price, sum_price FROM t_order_detail LIMIT 0;

-- Keep DROP separate from ADD: combined DROP/ADD is not supported by COPY.
ALTER TABLE t_order DROP FOREIGN KEY fk_order_user;
ALTER TABLE t_order
    MODIFY COLUMN user_id VARCHAR(100) NULL,
    ADD CONSTRAINT fk_order_history_user FOREIGN KEY (user_id)
        REFERENCES t_user(id) ON DELETE SET NULL;

ALTER TABLE t_order_detail DROP FOREIGN KEY fk_order_detail_product;
ALTER TABLE t_order_detail
    MODIFY COLUMN product_id INT NULL,
    ADD CONSTRAINT fk_order_history_product FOREIGN KEY (product_id)
        REFERENCES t_product(id) ON DELETE SET NULL;
