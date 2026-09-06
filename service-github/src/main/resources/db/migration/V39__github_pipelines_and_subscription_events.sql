CREATE TABLE github_pipelines (
    id          UUID PRIMARY KEY,
    repo_id     UUID NOT NULL REFERENCES github_repos(id) ON DELETE CASCADE,
    pipeline_id VARCHAR(64) NOT NULL,
    last_status VARCHAR(32) NOT NULL,
    updated_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_github_pipeline UNIQUE (repo_id, pipeline_id)
);

CREATE INDEX idx_github_pipelines_repo_id ON github_pipelines(repo_id);

ALTER TABLE github_subscriptions
    ADD COLUMN events JSONB NOT NULL DEFAULT '["issues", "change_requests", "releases"]'::jsonb;
