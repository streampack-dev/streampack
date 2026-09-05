CREATE TABLE github_instances (
    id            UUID PRIMARY KEY,
    host          VARCHAR(255) NOT NULL,
    api_url       VARCHAR(2048) NOT NULL,
    default_token VARCHAR(500),
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    active        BOOLEAN NOT NULL DEFAULT TRUE,
    CONSTRAINT uq_github_instance_host UNIQUE (host)
);

INSERT INTO github_instances (id, host, api_url)
VALUES (gen_random_uuid(), 'github.com', 'https://api.github.com');

ALTER TABLE github_repos
    ADD COLUMN instance_id UUID REFERENCES github_instances(id);

UPDATE github_repos
SET instance_id = (SELECT id FROM github_instances WHERE host = 'github.com');

ALTER TABLE github_repos
    ALTER COLUMN instance_id SET NOT NULL;

ALTER TABLE github_repos
    DROP CONSTRAINT uq_github_repo_owner_name;

ALTER TABLE github_repos
    ADD CONSTRAINT uq_github_repo_instance_owner_name UNIQUE (instance_id, owner, name);

CREATE INDEX idx_github_repos_instance_id ON github_repos(instance_id);
