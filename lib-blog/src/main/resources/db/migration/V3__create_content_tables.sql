-- Blog posts
CREATE TABLE posts (
    id                UUID PRIMARY KEY,
    title             VARCHAR(255) NOT NULL,
    markdown_source   TEXT NOT NULL,
    rendered_html     TEXT NOT NULL,
    excerpt           TEXT,
    status            VARCHAR(50) NOT NULL DEFAULT 'DRAFT',
    author_id         UUID REFERENCES users(id),
    published_at      TIMESTAMP WITH TIME ZONE,
    created_at        TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    deleted           BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE INDEX idx_posts_author_id ON posts(author_id);
CREATE INDEX idx_posts_status ON posts(status);
CREATE INDEX idx_posts_published ON posts(status, deleted, published_at)
    WHERE status = 'APPROVED' AND deleted = FALSE;
CREATE INDEX idx_posts_active ON posts(deleted) WHERE deleted = FALSE;

-- URL path aliases pointing to posts
CREATE TABLE slugs (
    id                UUID PRIMARY KEY,
    path              VARCHAR(255) NOT NULL UNIQUE,
    post_id           UUID NOT NULL REFERENCES posts(id),
    canonical         BOOLEAN NOT NULL DEFAULT FALSE,
    created_at        TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_slugs_post_id ON slugs(post_id);
CREATE INDEX idx_slugs_path ON slugs(path);

-- Nested comments on posts
CREATE TABLE comments (
    id                UUID PRIMARY KEY,
    post_id           UUID NOT NULL REFERENCES posts(id),
    author_id         UUID NOT NULL REFERENCES users(id),
    parent_comment_id UUID REFERENCES comments(id),
    markdown_source   TEXT NOT NULL,
    rendered_html     TEXT NOT NULL,
    created_at        TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    deleted           BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE INDEX idx_comments_post_id ON comments(post_id);
CREATE INDEX idx_comments_author_id ON comments(author_id);
CREATE INDEX idx_comments_parent ON comments(parent_comment_id);

-- Hierarchical content categories
CREATE TABLE categories (
    id                UUID PRIMARY KEY,
    name              VARCHAR(255) NOT NULL UNIQUE,
    slug              VARCHAR(255) NOT NULL UNIQUE,
    parent_id         UUID REFERENCES categories(id),
    deleted           BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE INDEX idx_categories_parent_id ON categories(parent_id);
CREATE INDEX idx_categories_slug ON categories(slug);
CREATE INDEX idx_categories_active ON categories(deleted) WHERE deleted = FALSE;

-- Flat content tags
CREATE TABLE tags (
    id                UUID PRIMARY KEY,
    name              VARCHAR(255) NOT NULL UNIQUE,
    slug              VARCHAR(255) NOT NULL UNIQUE,
    deleted           BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE INDEX idx_tags_slug ON tags(slug);
CREATE INDEX idx_tags_active ON tags(deleted) WHERE deleted = FALSE;

-- Post-to-category assignments
CREATE TABLE post_categories (
    id                UUID PRIMARY KEY,
    post_id           UUID NOT NULL REFERENCES posts(id),
    category_id       UUID NOT NULL REFERENCES categories(id),
    CONSTRAINT uq_post_category UNIQUE (post_id, category_id)
);

CREATE INDEX idx_post_categories_post_id ON post_categories(post_id);
CREATE INDEX idx_post_categories_category_id ON post_categories(category_id);

-- Post-to-tag assignments
CREATE TABLE post_tags (
    id                UUID PRIMARY KEY,
    post_id           UUID NOT NULL REFERENCES posts(id),
    tag_id            UUID NOT NULL REFERENCES tags(id),
    CONSTRAINT uq_post_tag UNIQUE (post_id, tag_id)
);

CREATE INDEX idx_post_tags_post_id ON post_tags(post_id);
CREATE INDEX idx_post_tags_tag_id ON post_tags(tag_id);
