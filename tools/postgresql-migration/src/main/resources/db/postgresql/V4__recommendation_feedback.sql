CREATE TABLE pubburi.t_user_recommendation_feedback (
    user_id VARCHAR(100) COLLATE pubburi.pubburi_ci NOT NULL,
    product_id INTEGER NOT NULL,
    feedback VARCHAR(14) COLLATE "C" NOT NULL,
    PRIMARY KEY (user_id, product_id),
    CONSTRAINT ck_recommendation_feedback CHECK (feedback IN ('MATCHED', 'NOT_INTERESTED')),
    CONSTRAINT fk_recommendation_feedback_user FOREIGN KEY (user_id)
        REFERENCES pubburi.t_user(id) ON DELETE CASCADE,
    CONSTRAINT fk_recommendation_feedback_product FOREIGN KEY (product_id)
        REFERENCES pubburi.t_product(id) ON DELETE CASCADE
);

CREATE INDEX idx_recommendation_feedback_product ON pubburi.t_user_recommendation_feedback (product_id);
