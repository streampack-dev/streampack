CREATE TABLE rss_feed_subscriptions (
    id              UUID PRIMARY KEY,
    feed_id         UUID NOT NULL REFERENCES rss_feeds(id) ON DELETE CASCADE,
    destination_uri VARCHAR(2048) NOT NULL,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    active          BOOLEAN NOT NULL DEFAULT TRUE,
    CONSTRAINT uq_rss_subscription UNIQUE (feed_id, destination_uri)
);

CREATE INDEX idx_rss_subscriptions_feed_id ON rss_feed_subscriptions(feed_id);
CREATE INDEX idx_rss_subscriptions_destination ON rss_feed_subscriptions(destination_uri);
