-- One-time MySQL 8.4 migration for the pre-snapshot schema.
-- Stop application writes, back up, run docs/ORDER-PRICE-SNAPSHOT.md preflight,
-- and execute without mysql --force. MySQL DDL commits implicitly.
-- Re-running this file intentionally fails on duplicate columns; do not ignore it.
ALTER TABLE t_order
    ADD COLUMN total_price INT DEFAULT NULL,
    ADD COLUMN price_basis VARCHAR(20) NOT NULL DEFAULT 'LEGACY_ESTIMATE';

ALTER TABLE t_order_detail
    ADD COLUMN product_name VARCHAR(100) NULL,
    ADD COLUMN product_type VARCHAR(20) NULL,
    ADD COLUMN product_img VARCHAR(100) NULL,
    ADD COLUMN unit_price INT NULL,
    ADD COLUMN sum_price INT NULL;

-- Historical prices cannot be reconstructed. Freeze the migration-time estimate
-- and expose LEGACY_ESTIMATE, never falsely mark it ORDER_SNAPSHOT.
UPDATE t_order_detail d JOIN t_product p ON p.id = d.product_id
SET d.product_name = p.name,
    d.product_type = p.type,
    d.product_img = p.img,
    d.unit_price = p.price,
    d.sum_price = p.price * d.quantity;

UPDATE t_order o JOIN (
    SELECT order_id, SUM(sum_price) AS total_price
    FROM t_order_detail GROUP BY order_id
) totals ON totals.order_id = o.o_id
SET o.total_price = totals.total_price;

-- Orders with no surviving details keep NULL total_price (unknown, not zero).
ALTER TABLE t_order_detail
    MODIFY COLUMN product_name VARCHAR(100) NOT NULL,
    MODIFY COLUMN product_type VARCHAR(20) NOT NULL,
    MODIFY COLUMN product_img VARCHAR(100) NOT NULL,
    MODIFY COLUMN unit_price INT NOT NULL,
    MODIFY COLUMN sum_price INT NOT NULL;
