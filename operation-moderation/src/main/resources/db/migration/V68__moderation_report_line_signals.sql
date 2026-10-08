-- Why each flagged line was flagged (#169): what it added, and which signals it raised, keyed by
-- line id. Null on reports filed before these were kept.
alter table moderation_report add column line_weights jsonb;
alter table moderation_report add column line_signals jsonb;
