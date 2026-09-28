-- Post search covers the title (weight A), tags and excerpt (B), and body text (C). The body is
-- rendered_html with markup stripped, which avoids markdown syntax and link URLs. Tags live in
-- post_tags, which a generated column can't read, so search_vector becomes an ordinary column
-- kept current by triggers. Hidden (_-prefixed) and deleted tags are not searchable.

ALTER TABLE posts DROP COLUMN search_vector;
ALTER TABLE posts ADD COLUMN search_vector tsvector;

CREATE FUNCTION posts_search_vector(p_id uuid, p_title text, p_excerpt text, p_html text)
    RETURNS tsvector
    LANGUAGE sql
    STABLE
AS $$
    SELECT setweight(to_tsvector('english', coalesce(p_title, '')), 'A')
        || setweight(to_tsvector('english', coalesce((
               SELECT string_agg(t.name, ' ')
               FROM post_tags pt
               JOIN tags t ON t.id = pt.tag_id
               WHERE pt.post_id = p_id AND t.deleted = FALSE AND left(t.name, 1) <> '_'
           ), '')), 'B')
        || setweight(to_tsvector('english', coalesce(p_excerpt, '')), 'B')
        || setweight(to_tsvector('english', regexp_replace(coalesce(p_html, ''), '<[^>]+>', ' ', 'g')), 'C')
$$;

-- Recomputed only when searchable content changes: access tracking updates posts on every read.
CREATE FUNCTION posts_search_vector_refresh() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    NEW.search_vector := posts_search_vector(NEW.id, NEW.title, NEW.excerpt, NEW.rendered_html);
    RETURN NEW;
END
$$;

CREATE TRIGGER posts_search_vector_refresh
    BEFORE INSERT OR UPDATE OF title, excerpt, rendered_html, search_vector ON posts
    FOR EACH ROW EXECUTE FUNCTION posts_search_vector_refresh();

-- Tagging a post, or untagging it, refreshes that post. Setting search_vector fires the trigger above.
CREATE FUNCTION post_tags_search_vector_refresh() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    UPDATE posts SET search_vector = NULL
    WHERE id IN (
        SELECT post_id FROM (VALUES (CASE WHEN TG_OP = 'DELETE' THEN NULL ELSE NEW.post_id END),
                                    (CASE WHEN TG_OP = 'INSERT' THEN NULL ELSE OLD.post_id END)) AS changed(post_id)
    );
    RETURN NULL;
END
$$;

CREATE TRIGGER post_tags_search_vector_refresh
    AFTER INSERT OR UPDATE OR DELETE ON post_tags
    FOR EACH ROW EXECUTE FUNCTION post_tags_search_vector_refresh();

-- Renaming or deleting a tag refreshes every post that carries it.
CREATE FUNCTION tags_search_vector_refresh() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    UPDATE posts SET search_vector = NULL
    WHERE id IN (SELECT post_id FROM post_tags WHERE tag_id = NEW.id);
    RETURN NULL;
END
$$;

CREATE TRIGGER tags_search_vector_refresh
    AFTER UPDATE OF name, deleted ON tags
    FOR EACH ROW EXECUTE FUNCTION tags_search_vector_refresh();

-- Index every existing post.
UPDATE posts SET search_vector = NULL;

CREATE INDEX idx_posts_search ON posts USING GIN (search_vector);
