-- Searching one channel's logs (GET /logs/search). Trigrams rather than a tsvector: chat is nicks,
-- URLs, code and fragments ("jvm", "graal", "NPE"), which word stemming in English doesn't suit,
-- and readers expect a substring to match wherever it appears. A GIN trigram index serves the
-- case-insensitive ILIKE '%term%' the search uses, for terms of three characters or more; the
-- search is always within one channel too, which idx_message_log_channel_ts narrows first.
-- pg_trgm is a trusted extension (PostgreSQL 13 and later), so the database's owner may create it.

CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE INDEX idx_message_log_content_trgm ON message_log USING GIN (content gin_trgm_ops);
