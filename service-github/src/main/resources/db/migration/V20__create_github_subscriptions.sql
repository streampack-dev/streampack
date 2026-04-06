CREATE TABLE github_subscriptions (
    id              UUID PRIMARY KEY,
    repo_id         UUID NOT NULL REFERENCES github_repos(id) ON DELETE CASCADE,
    destination_uri VARCHAR(2048) NOT NULL,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    active          BOOLEAN NOT NULL DEFAULT TRUE,
    CONSTRAINT uq_github_subscription UNIQUE (repo_id, destination_uri)
);

CREATE INDEX idx_github_subscriptions_repo_id ON github_subscriptions(repo_id);
CREATE INDEX idx_github_subscriptions_destination ON github_subscriptions(destination_uri);
