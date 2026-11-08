CREATE TABLE pubburi.t_user_cart (
    user_id VARCHAR(100) COLLATE pubburi.pubburi_ci PRIMARY KEY,
    version BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_cart_version CHECK (version BETWEEN 0 AND 9007199254740991),
    CONSTRAINT fk_cart_owner FOREIGN KEY (user_id) REFERENCES pubburi.t_user(id) ON DELETE CASCADE
);
CREATE TABLE pubburi.t_user_cart_item (
    user_id VARCHAR(100) COLLATE pubburi.pubburi_ci NOT NULL,
    product_id INTEGER NOT NULL,
    current_product_id INTEGER NULL,
    product_name VARCHAR(100) COLLATE "C" NOT NULL,
    quantity INTEGER NOT NULL,
    PRIMARY KEY (user_id,product_id),
    CONSTRAINT ck_cart_product CHECK (product_id>0 AND (current_product_id IS NULL OR current_product_id=product_id)),
    CONSTRAINT ck_cart_quantity CHECK (quantity BETWEEN 1 AND 99),
    CONSTRAINT fk_cart_item_owner FOREIGN KEY (user_id) REFERENCES pubburi.t_user_cart(user_id) ON DELETE CASCADE,
    CONSTRAINT fk_cart_item_product FOREIGN KEY (current_product_id) REFERENCES pubburi.t_product(id) ON DELETE SET NULL
);
CREATE INDEX idx_cart_item_current_product ON pubburi.t_user_cart_item (current_product_id);
CREATE TABLE pubburi.t_user_cart_operation (
    user_id VARCHAR(100) COLLATE pubburi.pubburi_ci NOT NULL,
    operation_id UUID NOT NULL,
    kind VARCHAR(8) COLLATE "C" NOT NULL,
    status VARCHAR(7) COLLATE "C" NOT NULL DEFAULT 'APPLIED',
    intent_hash CHAR(64) COLLATE "C" NOT NULL,
    order_id INTEGER NULL,
    PRIMARY KEY (user_id,operation_id),
    CONSTRAINT ck_cart_operation_kind CHECK (kind IN ('MERGE','CHECKOUT')),
    CONSTRAINT ck_cart_operation_status CHECK (status IN ('APPLIED','RETIRED')),
    CONSTRAINT ck_cart_operation_hash CHECK (intent_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_cart_operation_order CHECK ((status='RETIRED' AND order_id IS NULL) OR
        (status='APPLIED' AND ((kind='MERGE' AND order_id IS NULL) OR (kind='CHECKOUT' AND order_id IS NOT NULL AND order_id>0)))),
    CONSTRAINT fk_cart_operation_owner FOREIGN KEY (user_id) REFERENCES pubburi.t_user_cart(user_id) ON DELETE CASCADE
);
