-- Post versions (#196): a post in another form, starting with a recording of it (kind 'audio').
-- The post's markdown stays the original; a version belongs to the post and records which edit of
-- it it was made from (source_hash, a SHA-256 of the post's markdown_source when it was attached).
-- The content lives in media storage (a configured directory), addressed by storage_key, which the
-- server generates and never takes from a request. content_hash is the SHA-256 of the content,
-- for ETags. A post has at most one version per kind and variant; uploading again replaces it in
-- place (replaced_at), so its address doesn't change. Deleting a post deletes its versions.
CREATE TABLE post_version (
    id               UUID PRIMARY KEY,
    post_id          UUID NOT NULL REFERENCES posts(id) ON DELETE CASCADE,
    kind             VARCHAR(32) NOT NULL,
    variant          VARCHAR(64),
    media_type       VARCHAR(100) NOT NULL,
    size_bytes       BIGINT NOT NULL,
    duration_seconds INTEGER,
    note             VARCHAR(500),
    -- Who made it: the uploader, or (for kinds that could be generated, none yet) a model.
    maker_id         UUID REFERENCES users(id) ON DELETE SET NULL,
    maker_model      VARCHAR(255),
    source_hash      VARCHAR(64) NOT NULL,
    content_hash     VARCHAR(64) NOT NULL,
    storage_key      VARCHAR(255) NOT NULL UNIQUE,
    created_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    replaced_at      TIMESTAMP WITH TIME ZONE,
    updated_at       TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE UNIQUE INDEX uq_post_version_kind ON post_version(post_id, kind, COALESCE(variant, ''));
CREATE INDEX idx_post_version_kind ON post_version(kind, post_id);
