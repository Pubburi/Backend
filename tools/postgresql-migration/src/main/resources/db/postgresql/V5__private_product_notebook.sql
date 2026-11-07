CREATE TABLE pubburi.t_user_notebook (
    id UUID PRIMARY KEY,
    user_id VARCHAR(100) COLLATE pubburi.pubburi_ci NOT NULL,
    product_id INTEGER NULL,
    product_name VARCHAR(100) COLLATE "C" NOT NULL,
    experience VARCHAR(10) COLLATE "C" NOT NULL,
    rating NUMERIC(3,2) NULL,
    taste_tags VARCHAR(40)[] COLLATE "C" NULL,
    aroma_tags VARCHAR(40)[] COLLATE "C" NULL,
    memo VARCHAR(2000) COLLATE "C" NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_notebook_owner_product UNIQUE (user_id, product_id),
    CONSTRAINT ck_notebook_experience CHECK (experience IN ('TRIED','INTERESTED')),
    CONSTRAINT ck_notebook_rating CHECK (rating IS NULL OR rating BETWEEN 0 AND 5),
    CONSTRAINT ck_notebook_interest CHECK (experience = 'TRIED' OR (rating IS NULL AND taste_tags IS NULL AND aroma_tags IS NULL)),
    CONSTRAINT ck_notebook_version CHECK (version BETWEEN 0 AND 9007199254740991),
    CONSTRAINT ck_notebook_taste_tags CHECK (taste_tags IS NULL OR
        ((cardinality(taste_tags)=0 OR array_ndims(taste_tags)=1) AND cardinality(taste_tags)<=20
         AND array_position(taste_tags,NULL) IS NULL AND NOT ('' = ANY(taste_tags)))),
    CONSTRAINT ck_notebook_aroma_tags CHECK (aroma_tags IS NULL OR
        ((cardinality(aroma_tags)=0 OR array_ndims(aroma_tags)=1) AND cardinality(aroma_tags)<=20
         AND array_position(aroma_tags,NULL) IS NULL AND NOT ('' = ANY(aroma_tags)))),
    CONSTRAINT fk_notebook_owner FOREIGN KEY (user_id) REFERENCES pubburi.t_user(id) ON DELETE CASCADE,
    CONSTRAINT fk_notebook_product FOREIGN KEY (product_id) REFERENCES pubburi.t_product(id) ON DELETE SET NULL
);
CREATE INDEX idx_notebook_owner_updated ON pubburi.t_user_notebook (user_id,updated_at DESC,id);
CREATE INDEX idx_notebook_product ON pubburi.t_user_notebook (product_id);
