ALTER TABLE posts
    ADD COLUMN access_count BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN last_accessed_at TIMESTAMPTZ NULL;

CREATE INDEX idx_posts_access_count ON posts(access_count DESC, created_at DESC);
