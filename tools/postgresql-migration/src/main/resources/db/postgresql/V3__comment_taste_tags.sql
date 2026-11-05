-- Member opinions are not curated product features or recommendation labels.
CREATE TABLE pubburi.t_comment_taste_tag (
    comment_id INTEGER NOT NULL,
    tag VARCHAR(40) COLLATE "C" NOT NULL,
    PRIMARY KEY (comment_id, tag),
    CONSTRAINT ck_comment_taste_tag_nonblank CHECK (char_length(btrim(tag)) > 0),
    CONSTRAINT fk_comment_taste_tag FOREIGN KEY (comment_id)
        REFERENCES pubburi.t_comment(id) ON DELETE CASCADE
);
