ALTER TABLE github_repos
    ADD COLUMN delivery_mode VARCHAR(20) NOT NULL DEFAULT 'POLLING',
    ADD COLUMN webhook_secret TEXT,
    ADD COLUMN webhook_configured_at TIMESTAMP WITH TIME ZONE;
