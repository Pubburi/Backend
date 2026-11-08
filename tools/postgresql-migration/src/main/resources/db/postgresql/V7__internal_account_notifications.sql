-- Server-authored events only. Source IDs and product names survive target deletion.
CREATE TABLE t_user_notification (
    id UUID PRIMARY KEY,
    user_id VARCHAR(100) COLLATE pubburi_ci NOT NULL,
    event_type VARCHAR(16) COLLATE "C" NOT NULL,
    event_id INTEGER NOT NULL,
    order_id INTEGER NULL,
    product_id INTEGER NULL,
    product_name VARCHAR(100) NULL,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL DEFAULT statement_timestamp(),
    read_at TIMESTAMP(6) WITH TIME ZONE NULL,
    CONSTRAINT fk_notification_user FOREIGN KEY (user_id) REFERENCES t_user(id) ON DELETE CASCADE,
    CONSTRAINT uq_notification_event UNIQUE (user_id, event_type, event_id),
    CONSTRAINT ck_notification_event_id CHECK (event_id > 0),
    CONSTRAINT ck_notification_target CHECK (
        (event_type = 'ORDER_COMPLETED' AND order_id IS NOT NULL AND order_id = event_id AND product_id IS NULL AND product_name IS NULL)
        OR (event_type = 'REVIEW_REMOVED' AND order_id IS NULL AND product_id IS NOT NULL AND product_id > 0 AND product_name IS NOT NULL)
    ),
    CONSTRAINT ck_notification_read_at CHECK (read_at IS NULL OR read_at >= created_at)
);
CREATE INDEX ix_notification_owner_created ON t_user_notification (user_id, created_at DESC, id DESC);
