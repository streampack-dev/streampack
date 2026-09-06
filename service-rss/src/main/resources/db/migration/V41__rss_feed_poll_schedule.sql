ALTER TABLE rss_feeds
    ADD COLUMN next_poll_at  TIMESTAMP WITH TIME ZONE,
    ADD COLUMN poll_failures INT NOT NULL DEFAULT 0;

-- Spread existing feeds across the next hour so they do not all come due at once
UPDATE rss_feeds
SET next_poll_at = NOW() + (random() * INTERVAL '1 hour');

ALTER TABLE rss_feeds
    ALTER COLUMN next_poll_at SET NOT NULL,
    ALTER COLUMN next_poll_at SET DEFAULT NOW();

CREATE INDEX idx_rss_feeds_due ON rss_feeds(active, next_poll_at);
