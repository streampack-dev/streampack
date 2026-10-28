-- An entry stored with a relative link (#290), as Atom allows, takes its feed's address as the
-- base, as entries polled from now on do. Only links with no scheme change; guids stay as stored.
-- shortcut: '../' is left in the joined path for the browser to resolve; rewrite it here only if a
-- feed is found using it.
UPDATE rss_entries e
SET link = CASE
    WHEN e.link LIKE '//%' THEN substring(f.feed_url FROM '^([A-Za-z][A-Za-z0-9+.-]*):') || ':' || e.link
    WHEN e.link LIKE '/%' THEN substring(f.feed_url FROM '^[A-Za-z][A-Za-z0-9+.-]*://[^/?#]+') || e.link
    ELSE substring(f.feed_url FROM '^[A-Za-z][A-Za-z0-9+.-]*://[^/?#]+/[^?#]*/|^[A-Za-z][A-Za-z0-9+.-]*://[^/?#]+') ||
        CASE WHEN f.feed_url ~ '^[A-Za-z][A-Za-z0-9+.-]*://[^/?#]+/[^?#]*/' THEN '' ELSE '/' END || e.link
END
FROM rss_feeds f
WHERE e.feed_id = f.id
  AND btrim(e.link) <> ''
  AND e.link !~ '^[A-Za-z][A-Za-z0-9+.-]*:'
  AND f.feed_url ~ '^[A-Za-z][A-Za-z0-9+.-]*://[^/?#]+';
