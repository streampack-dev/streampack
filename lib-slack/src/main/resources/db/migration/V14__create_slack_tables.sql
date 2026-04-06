CREATE TABLE slack_workspaces (
    id              UUID PRIMARY KEY,
    name            VARCHAR(100) NOT NULL,
    bot_token       VARCHAR(500) NOT NULL,
    app_token       VARCHAR(500) NOT NULL,
    signal_character VARCHAR(10),
    autoconnect     BOOLEAN NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    deleted         BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT uq_slack_workspace_name UNIQUE (name)
);

CREATE TABLE slack_channels (
    id              UUID PRIMARY KEY,
    workspace_id    UUID NOT NULL REFERENCES slack_workspaces(id),
    name            VARCHAR(200) NOT NULL,
    channel_id      VARCHAR(50),
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    deleted         BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE UNIQUE INDEX idx_slack_channels_workspace_name
    ON slack_channels(workspace_id, name) WHERE deleted = FALSE;

CREATE INDEX idx_slack_workspaces_autoconnect
    ON slack_workspaces(autoconnect) WHERE autoconnect = TRUE AND deleted = FALSE;
