-- Migration control metadata only. No rewrite of V1 or business/source identities.
CREATE TABLE migration_source_receipt (
    singleton_id SMALLINT PRIMARY KEY CHECK (singleton_id=1),
    artifact_sha256 CHAR(64) COLLATE "C" NOT NULL CHECK (artifact_sha256 ~ '^[0-9a-f]{64}$'),
    source_id UUID NOT NULL,
    source_server UUID NOT NULL,
    window_id UUID NOT NULL,
    row_count INTEGER NOT NULL CHECK (row_count >= 0 AND row_count <= 10000),
    imported_at TIMESTAMP(6) WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
