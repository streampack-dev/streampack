ALTER TABLE gitlab_projects
    ADD COLUMN next_poll_at  TIMESTAMP WITH TIME ZONE,
    ADD COLUMN poll_failures INT NOT NULL DEFAULT 0;

-- Spread existing projects across the next hour so they do not all come due at once
UPDATE gitlab_projects
SET next_poll_at = NOW() + (random() * INTERVAL '1 hour');

ALTER TABLE gitlab_projects
    ALTER COLUMN next_poll_at SET NOT NULL,
    ALTER COLUMN next_poll_at SET DEFAULT NOW();

CREATE INDEX idx_gitlab_projects_due ON gitlab_projects(active, delivery_mode, next_poll_at);
