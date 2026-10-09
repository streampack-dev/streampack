-- Feed entries' own tags (#139): RSS <category> and Atom <category term>, kept per entry.

-- One row per entry and normalized name (TagNames.normalize): raw is the first form the feed
-- wrote it in. An entry's categories are kept in step with the feed while it's in the feed's
-- window; entries stored before this have none until a poll sees them again.
CREATE TABLE rss_entry_category (
    id        UUID PRIMARY KEY,
    entry_id  UUID NOT NULL REFERENCES rss_entries(id) ON DELETE CASCADE,
    name      VARCHAR(255) NOT NULL,
    raw       VARCHAR(255) NOT NULL,
    CONSTRAINT uq_rss_entry_category UNIQUE (entry_id, name)
);

CREATE INDEX idx_rss_entry_category_name ON rss_entry_category(name);

-- Feed tags the vocabulary doesn't know (#139), and what became of them. A feed tag that's a tag,
-- an alias or stoplisted is decided by the vocabulary's own tables; this holds the rest. WAITING
-- until it's on enough entries across enough feeds (PROMOTED, created as a tag) or an admin maps
-- it to a tag (MAPPED, an alias), ignores it (IGNORED, stoplisted) or creates it (CREATED).
-- entries and feeds are the counts when it was last seen; tag is the BCN tag it became.
CREATE TABLE rss_feed_tag (
    name        VARCHAR(255) PRIMARY KEY,
    status      VARCHAR(20) NOT NULL DEFAULT 'WAITING',
    entries     INTEGER NOT NULL DEFAULT 0,
    feeds       INTEGER NOT NULL DEFAULT 0,
    first_seen  TIMESTAMP WITH TIME ZONE NOT NULL,
    last_seen   TIMESTAMP WITH TIME ZONE NOT NULL,
    tag         VARCHAR(255),
    decided_by  VARCHAR(255),
    decided_at  TIMESTAMP WITH TIME ZONE
);

CREATE INDEX idx_rss_feed_tag_status ON rss_feed_tag(status, entries DESC);
