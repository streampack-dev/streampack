CREATE TABLE github_repos (
    id                   UUID PRIMARY KEY,
    owner                VARCHAR(255) NOT NULL,
    name                 VARCHAR(255) NOT NULL,
    token                VARCHAR(500),
    highest_issue_number INT NOT NULL DEFAULT 0,
    highest_pr_number    INT NOT NULL DEFAULT 0,
    last_polled_at       TIMESTAMP WITH TIME ZONE,
    created_at           TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    active               BOOLEAN NOT NULL DEFAULT TRUE,
    CONSTRAINT uq_github_repo_owner_name UNIQUE (owner, name)
);

CREATE TABLE github_releases (
    id         UUID PRIMARY KEY,
    repo_id    UUID NOT NULL REFERENCES github_repos(id) ON DELETE CASCADE,
    tag        VARCHAR(255) NOT NULL,
    name       VARCHAR(500),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_github_release_repo_tag UNIQUE (repo_id, tag)
);

CREATE INDEX idx_github_releases_repo_id ON github_releases(repo_id);
