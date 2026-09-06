ALTER TABLE github_repos
    ADD COLUMN next_poll_at  TIMESTAMP WITH TIME ZONE,
    ADD COLUMN poll_failures INT NOT NULL DEFAULT 0;

-- Spread existing repositories across the next hour so they do not all come due at once
UPDATE github_repos
SET next_poll_at = NOW() + (random() * INTERVAL '1 hour');

ALTER TABLE github_repos
    ALTER COLUMN next_poll_at SET NOT NULL,
    ALTER COLUMN next_poll_at SET DEFAULT NOW();

CREATE INDEX idx_github_repos_due ON github_repos(active, delivery_mode, next_poll_at);
