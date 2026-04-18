ALTER TABLE rss_entries
    ADD COLUMN access_count BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN last_accessed_at TIMESTAMP WITH TIME ZONE;

CREATE INDEX idx_rss_entries_access_count ON rss_entries(access_count DESC, created_at DESC);
