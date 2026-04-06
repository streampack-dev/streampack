-- Add sort_order for deterministic ordering of system pages (e.g., sidebar items)
ALTER TABLE posts ADD COLUMN sort_order INTEGER NOT NULL DEFAULT 0;

-- Add metadata JSONB column for extensible per-post settings
ALTER TABLE posts ADD COLUMN metadata JSONB NOT NULL DEFAULT '{}';

CREATE INDEX idx_posts_sort_order ON posts(sort_order);
