-- Apply once after 001–008 with all writers stopped and a verified backup.
-- Never replay data.sql, use --force, or initialize an existing database.
-- Member review opinions; separate from curated product taste/aroma.
CREATE TABLE t_comment_taste_tag (
    comment_id INT NOT NULL,
    tag VARCHAR(40) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    PRIMARY KEY (comment_id, tag),
    CONSTRAINT ck_comment_taste_tag_nonblank CHECK (CHAR_LENGTH(TRIM(tag)) > 0),
    CONSTRAINT fk_comment_taste_tag FOREIGN KEY (comment_id)
        REFERENCES t_comment(id) ON DELETE CASCADE
);
