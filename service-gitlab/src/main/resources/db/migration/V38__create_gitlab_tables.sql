CREATE TABLE gitlab_instances (
    id            UUID PRIMARY KEY,
    host          VARCHAR(255) NOT NULL,
    api_url       VARCHAR(2048) NOT NULL,
    default_token VARCHAR(500),
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    active        BOOLEAN NOT NULL DEFAULT TRUE,
    CONSTRAINT uq_gitlab_instance_host UNIQUE (host)
);

INSERT INTO gitlab_instances (id, host, api_url)
VALUES (gen_random_uuid(), 'gitlab.com', 'https://gitlab.com/api/v4');

CREATE TABLE gitlab_projects (
    id                    UUID PRIMARY KEY,
    instance_id           UUID NOT NULL REFERENCES gitlab_instances(id),
    full_path             VARCHAR(1024) NOT NULL,
    gitlab_project_id     BIGINT,
    token                 VARCHAR(500),
    highest_issue_number  INT NOT NULL DEFAULT 0,
    highest_mr_number     INT NOT NULL DEFAULT 0,
    last_polled_at        TIMESTAMP WITH TIME ZONE,
    delivery_mode         VARCHAR(20) NOT NULL DEFAULT 'POLLING',
    webhook_secret        TEXT,
    webhook_configured_at TIMESTAMP WITH TIME ZONE,
    created_at            TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    active                BOOLEAN NOT NULL DEFAULT TRUE,
    CONSTRAINT uq_gitlab_project_instance_path UNIQUE (instance_id, full_path)
);

CREATE INDEX idx_gitlab_projects_instance_id ON gitlab_projects(instance_id);
CREATE INDEX idx_gitlab_projects_gitlab_project_id ON gitlab_projects(instance_id, gitlab_project_id);

CREATE TABLE gitlab_releases (
    id         UUID PRIMARY KEY,
    project_id UUID NOT NULL REFERENCES gitlab_projects(id) ON DELETE CASCADE,
    tag        VARCHAR(255) NOT NULL,
    name       VARCHAR(500),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_gitlab_release_project_tag UNIQUE (project_id, tag)
);

CREATE INDEX idx_gitlab_releases_project_id ON gitlab_releases(project_id);

CREATE TABLE gitlab_subscriptions (
    id              UUID PRIMARY KEY,
    project_id      UUID NOT NULL REFERENCES gitlab_projects(id) ON DELETE CASCADE,
    destination_uri VARCHAR(2048) NOT NULL,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    active          BOOLEAN NOT NULL DEFAULT TRUE,
    CONSTRAINT uq_gitlab_subscription UNIQUE (project_id, destination_uri)
);

CREATE INDEX idx_gitlab_subscriptions_project_id ON gitlab_subscriptions(project_id);
CREATE INDEX idx_gitlab_subscriptions_destination ON gitlab_subscriptions(destination_uri);
