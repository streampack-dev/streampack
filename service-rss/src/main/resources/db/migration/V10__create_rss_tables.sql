CREATE TABLE rss_feeds (
    id              UUID PRIMARY KEY,
    feed_url        VARCHAR(2048) NOT NULL,
    site_url        VARCHAR(2048),
    title           VARCHAR(500) NOT NULL,
    description     VARCHAR(2000),
    last_fetched_at TIMESTAMP WITH TIME ZONE,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    active          BOOLEAN NOT NULL DEFAULT TRUE,
    CONSTRAINT uq_rss_feed_url UNIQUE (feed_url)
);

CREATE TABLE rss_entries (
    id           UUID PRIMARY KEY,
    feed_id      UUID NOT NULL REFERENCES rss_feeds(id) ON DELETE CASCADE,
    guid         VARCHAR(2048) NOT NULL,
    link         VARCHAR(2048) NOT NULL,
    title        VARCHAR(500) NOT NULL,
    published_at TIMESTAMP WITH TIME ZONE,
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX idx_rss_entries_feed_guid ON rss_entries(feed_id, guid);
CREATE INDEX idx_rss_entries_feed_id ON rss_entries(feed_id);
