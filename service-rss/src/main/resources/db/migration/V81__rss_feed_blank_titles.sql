-- A feed whose <title> was empty was stored with no name (#283): name it by its host, without
-- "www.", as feeds added from now on are named.
UPDATE rss_feeds
SET title = regexp_replace(substring(feed_url FROM '^[A-Za-z][A-Za-z0-9+.-]*://([^/:?#]+)'), '^www\.', '')
WHERE btrim(title) = ''
  AND substring(feed_url FROM '^[A-Za-z][A-Za-z0-9+.-]*://([^/:?#]+)') IS NOT NULL;
