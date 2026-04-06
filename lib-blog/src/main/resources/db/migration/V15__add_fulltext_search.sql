-- Full-text search on posts using title (A weight) and excerpt (B weight)
ALTER TABLE posts ADD COLUMN search_vector tsvector
    GENERATED ALWAYS AS (
        setweight(to_tsvector('english', coalesce(title, '')), 'A') ||
        setweight(to_tsvector('english', coalesce(excerpt, '')), 'B')
    ) STORED;

CREATE INDEX idx_posts_search ON posts USING GIN (search_vector);
