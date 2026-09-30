-- An entry's summary, as plain text, from its feed's description (RSS) or summary or content
-- (Atom), for readers to see what an item is about before following it (#98). Existing entries
-- have none.
ALTER TABLE rss_entries
    ADD COLUMN summary VARCHAR(500);
