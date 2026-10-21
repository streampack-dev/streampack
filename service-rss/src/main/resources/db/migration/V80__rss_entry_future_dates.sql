-- An entry dated after it was received sorts as received (#261). Entries stored before the
-- polling service refused such dates keep them, so clear them: the aggregate then falls back
-- to created_at. Entries without a date are dated again by the next poll that still sees them.
UPDATE rss_entries SET published_at = NULL WHERE published_at > created_at;
