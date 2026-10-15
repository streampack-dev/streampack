-- Plain text for search: what a reader sees of a post, for exact matching of quoted words (#227)
-- and for the passage that shows why a result matched (#226).

-- Rendered HTML as text: block tags (paragraphs, list items, headings, cells, line breaks) become
-- spaces, so their words stay apart; inline tags (links, emphasis, code) go, so "<em>x</em>." reads
-- "x."; the entities the renderer writes are decoded (&amp; last, so "&amp;lt;" stays "&lt;"); and
-- runs of whitespace close up.
CREATE FUNCTION html_text(p_html text)
    RETURNS text
    LANGUAGE sql
    IMMUTABLE
AS $$
    SELECT btrim(regexp_replace(
        replace(replace(replace(replace(replace(replace(
            regexp_replace(
                regexp_replace(coalesce(p_html, ''),
                    '</?(p|br|hr|li|ul|ol|dl|dt|dd|h[1-6]|div|pre|blockquote|table|thead|tbody|tr|td|th|figure|figcaption|section|article|aside|header|footer|details|summary)(\s[^>]*)?/?>',
                    ' ', 'gi'),
                '<[^>]+>', '', 'g'),
            '&lt;', '<'), '&gt;', '>'), '&quot;', '"'), '&#39;', ''''), '&nbsp;', ' '), '&amp;', '&'),
        '\s+', ' ', 'g'))
$$;

-- The visible tags of a post, as posts_search_vector reads them: not deleted, not hidden (_).
CREATE FUNCTION posts_search_tags(p_id uuid)
    RETURNS text
    LANGUAGE sql
    STABLE
AS $$
    SELECT coalesce(string_agg(t.name, ' ' ORDER BY t.name), '')
    FROM post_tags pt
    JOIN tags t ON t.id = pt.tag_id
    WHERE pt.post_id = p_id AND t.deleted = FALSE AND left(t.name, 1) <> '_'
$$;

-- Everything search covers, as one text for the exact match. The fields are kept apart by a
-- character that isn't whitespace, so a quoted phrase can't run from one field into the next.
CREATE FUNCTION posts_search_text(p_id uuid, p_title text, p_excerpt text, p_html text)
    RETURNS text
    LANGUAGE sql
    STABLE
AS $$
    SELECT coalesce(p_title, '') || ' ¶ ' || coalesce(p_excerpt, '') || ' ¶ '
        || posts_search_tags(p_id) || ' ¶ ' || html_text(p_html)
$$;

-- The search vector reads the body as html_text does, so the index and the exact match see the same
-- words: before, every tag became a space, and "<strong>un</strong>expected" was indexed as "un" and
-- "expected", which a search for "unexpected" couldn't find.
CREATE OR REPLACE FUNCTION posts_search_vector(p_id uuid, p_title text, p_excerpt text, p_html text)
    RETURNS tsvector
    LANGUAGE sql
    STABLE
AS $$
    SELECT setweight(to_tsvector('english', coalesce(p_title, '')), 'A')
        || setweight(to_tsvector('english', posts_search_tags(p_id)), 'B')
        || setweight(to_tsvector('english', coalesce(p_excerpt, '')), 'B')
        || setweight(to_tsvector('english', html_text(p_html)), 'C')
$$;

-- Index every existing post again; setting search_vector fires V47's refresh trigger.
UPDATE posts SET search_vector = NULL;
