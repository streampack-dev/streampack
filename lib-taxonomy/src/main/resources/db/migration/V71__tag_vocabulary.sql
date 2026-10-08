-- The tag vocabulary (#140): aliases, the stoplist, the review queue and the log of admin actions.

-- tags moved from lib-blog to lib-taxonomy with no schema change. lib-blog's V3 creates it; this
-- creates the same table only where V3 isn't on the classpath (a module with lib-taxonomy but not
-- lib-blog), so the Tag entity validates there too. Where V3 ran, as in production, it does nothing.
CREATE TABLE IF NOT EXISTS tags (
    id                UUID PRIMARY KEY,
    name              VARCHAR(255) NOT NULL UNIQUE,
    slug              VARCHAR(255) NOT NULL UNIQUE,
    deleted           BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE INDEX IF NOT EXISTS idx_tags_slug ON tags(slug);
CREATE INDEX IF NOT EXISTS idx_tags_active ON tags(deleted) WHERE deleted = FALSE;

-- A name that means another tag: writing the alias stores the tag, looking it up finds the tag's
-- posts and factoids. The alias is a normalized tag name (TagNames.normalize).
CREATE TABLE tag_alias (
    alias       VARCHAR(255) PRIMARY KEY,
    tag_id      UUID NOT NULL REFERENCES tags(id) ON DELETE CASCADE,
    created_by  VARCHAR(255) NOT NULL,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX tag_alias_tag ON tag_alias(tag_id);

-- Terms dropped from every tag list that's written. Existing uses are left alone.
CREATE TABLE tag_stop (
    term        VARCHAR(255) PRIMARY KEY,
    created_by  VARCHAR(255) NOT NULL,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL
);

-- New tags that look doubtful, for an admin to decide on; never shown to the contributor. One
-- entry per tag. hint_kind is PLURAL (a trailing-s pair with hint_tags[0]), MISSING_COMMA
-- (hint_tags are the existing tags it's made of) or AI (only the AI near-miss found something).
-- The ai_* columns are the moderation model's answer, when AI is on.
CREATE TABLE tag_review (
    id             UUID PRIMARY KEY,
    tag            VARCHAR(255) NOT NULL UNIQUE,
    first_seen     TIMESTAMP WITH TIME ZONE NOT NULL,
    source         VARCHAR(50) NOT NULL,
    hint_kind      VARCHAR(20) NOT NULL,
    hint_tags      JSONB NOT NULL DEFAULT '[]'::jsonb,
    ai_candidate   VARCHAR(255),
    ai_confidence  DOUBLE PRECISION,
    ai_reason      TEXT,
    ai_model       VARCHAR(100),
    status         VARCHAR(20) NOT NULL DEFAULT 'OPEN',
    acted_by       VARCHAR(255),
    acted_at       TIMESTAMP WITH TIME ZONE
);

CREATE INDEX tag_review_status ON tag_review(status, first_seen DESC);

-- Every change to the vocabulary: who made it, and when.
CREATE TABLE tag_action (
    id         UUID PRIMARY KEY,
    action     VARCHAR(20) NOT NULL,
    subject    VARCHAR(255) NOT NULL,
    detail     TEXT,
    actor      VARCHAR(255) NOT NULL,
    acted_at   TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX tag_action_acted_at ON tag_action(acted_at DESC);
