CREATE TABLE gitlab_pipelines (
    id          UUID PRIMARY KEY,
    project_id  UUID NOT NULL REFERENCES gitlab_projects(id) ON DELETE CASCADE,
    pipeline_id VARCHAR(64) NOT NULL,
    last_status VARCHAR(32) NOT NULL,
    updated_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_gitlab_pipeline UNIQUE (project_id, pipeline_id)
);

CREATE INDEX idx_gitlab_pipelines_project_id ON gitlab_pipelines(project_id);

ALTER TABLE gitlab_subscriptions
    ADD COLUMN events JSONB NOT NULL DEFAULT '["issues", "change_requests", "releases"]'::jsonb;
