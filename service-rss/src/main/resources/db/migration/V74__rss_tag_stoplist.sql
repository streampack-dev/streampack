-- Feed boilerplate never becomes a tag (#139): these are stoplisted, so a feed's `Uncategorized`
-- or `Blog` is ignored, and so is a post's. `news` is a real tag and is not here. A term that's
-- already stoplisted, or is an alias, is left as it is. Recorded in tag_action as by `migration`.
INSERT INTO tag_stop (term, created_by, created_at)
SELECT term, 'migration', NOW()
FROM (VALUES ('uncategorized'), ('blog'), ('featured'), ('post'), ('posts'), ('general'),
             ('misc'), ('other'), ('article'), ('articles'), ('update'), ('updates')) AS t(term)
WHERE NOT EXISTS (SELECT 1 FROM tag_stop s WHERE s.term = t.term)
  AND NOT EXISTS (SELECT 1 FROM tag_alias a WHERE a.alias = t.term);

INSERT INTO tag_action (id, action, subject, detail, actor, acted_at)
SELECT gen_random_uuid(), 'STOP', s.term, 'feed boilerplate (#139)', 'migration', s.created_at
FROM tag_stop s
WHERE s.created_by = 'migration'
  AND s.term IN ('uncategorized', 'blog', 'featured', 'post', 'posts', 'general', 'misc', 'other',
                 'article', 'articles', 'update', 'updates')
  AND NOT EXISTS (SELECT 1 FROM tag_action x WHERE x.action = 'STOP' AND x.subject = s.term);
