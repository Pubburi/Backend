-- MySQL 8.4 after 007. Stop all business writers/relays; back up and validate first.
-- New delivery metadata only. Immutable outbox events/counters are not changed.
-- One-time DDL: do not use --force, IF NOT EXISTS, or blindly reset an existing queue.
CREATE TABLE t_product_search_delivery (
    event_id BIGINT PRIMARY KEY,
    status VARCHAR(12) NOT NULL DEFAULT 'PENDING',
    claim_token CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    lease_until TIMESTAMP(6) NULL,
    available_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    attempts INT NOT NULL DEFAULT 0,
    delivered_at TIMESTAMP(6) NULL,
    last_error VARCHAR(64) NULL,
    INDEX idx_search_delivery_pending (status, available_at, event_id),
    INDEX idx_search_delivery_lease (status, lease_until, event_id),
    CONSTRAINT fk_search_delivery_event FOREIGN KEY (event_id)
        REFERENCES t_product_search_outbox(event_id) ON DELETE RESTRICT,
    CONSTRAINT ck_search_delivery_attempts CHECK (attempts >= 0),
    CONSTRAINT ck_search_delivery_state CHECK (
        (status='PENDING' AND claim_token IS NULL AND lease_until IS NULL AND delivered_at IS NULL) OR
        (status='IN_FLIGHT' AND claim_token IS NOT NULL AND lease_until IS NOT NULL AND delivered_at IS NULL) OR
        (status='DELIVERED' AND claim_token IS NULL AND lease_until IS NULL AND delivered_at IS NOT NULL))
) ENGINE=InnoDB;

CREATE TABLE t_product_search_relay_target (
    singleton_id TINYINT PRIMARY KEY,
    source_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    identity_json JSON NULL,
    bound_at TIMESTAMP(6) NULL,
    CONSTRAINT ck_search_target_singleton CHECK (singleton_id=1),
    CONSTRAINT ck_search_target_binding CHECK (
        (fingerprint IS NULL AND identity_json IS NULL AND bound_at IS NULL) OR
        (fingerprint IS NOT NULL AND identity_json IS NOT NULL AND bound_at IS NOT NULL))
) ENGINE=InnoDB;

START TRANSACTION;
INSERT INTO t_product_search_relay_target(singleton_id, source_id) VALUES (1, UUID());
INSERT INTO t_product_search_delivery(event_id)
SELECT event_id FROM t_product_search_outbox ORDER BY event_id;
COMMIT;
