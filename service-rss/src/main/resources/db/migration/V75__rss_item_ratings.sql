-- Feed item ratings (#187).

-- An admin's rating of a feed item: RATES (worth writing about), MIGHT or DULL. One per
-- item; a newer rating replaces it, and every change is kept in rss_item_rating_history. Never
-- public: only the admin endpoints read these tables.
CREATE TABLE rss_item_rating (
    item_id   UUID PRIMARY KEY REFERENCES rss_entries(id) ON DELETE CASCADE,
    rating    VARCHAR(10) NOT NULL,
    rated_by  VARCHAR(255) NOT NULL,
    rated_at  TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_rss_item_rating_rating ON rss_item_rating(rating, rated_at DESC);

-- Every change to an item's rating: rating is what it became (null when it was cleared), previous
-- what it was (null when it had none).
CREATE TABLE rss_item_rating_history (
    id        UUID PRIMARY KEY,
    item_id   UUID NOT NULL REFERENCES rss_entries(id) ON DELETE CASCADE,
    rating    VARCHAR(10),
    previous  VARCHAR(10),
    acted_by  VARCHAR(255) NOT NULL,
    acted_at  TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_rss_item_rating_history_item ON rss_item_rating_history(item_id, acted_at);

-- The model's hidden guess at an item's rating (#187), made once, off by default
-- (streampack.rss.rating.model-guess). Never shown where items are rated, so it can't bias the
-- ratings: only the CSV export and the rating stats read it.
CREATE TABLE rss_item_guess (
    item_id     UUID PRIMARY KEY REFERENCES rss_entries(id) ON DELETE CASCADE,
    label       VARCHAR(10) NOT NULL,
    confidence  DOUBLE PRECISION NOT NULL,
    reason      VARCHAR(1000) NOT NULL,
    model       VARCHAR(255),
    text_source VARCHAR(10) NOT NULL,
    guessed_at  TIMESTAMP WITH TIME ZONE NOT NULL
);

-- An item's full text, for the guess (#187): the feed's own content (RSS content:encoded, Atom
-- content) as plain text when it gave any (source CONTENT, stored as the item is), else the
-- article page's text, fetched once when a guess first needs it (PAGE), or no text at all when
-- that fetch found none (NONE, never fetched again). At most 20,000 characters. Never public.
CREATE TABLE rss_entry_text (
    entry_id   UUID PRIMARY KEY REFERENCES rss_entries(id) ON DELETE CASCADE,
    source     VARCHAR(10) NOT NULL,
    content    TEXT,
    stored_at  TIMESTAMP WITH TIME ZONE NOT NULL
);
