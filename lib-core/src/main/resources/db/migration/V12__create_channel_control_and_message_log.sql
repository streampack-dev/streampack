CREATE TABLE channel_control_options (
    id              UUID PRIMARY KEY,
    provenance_uri  VARCHAR(500) NOT NULL,
    autojoin        BOOLEAN NOT NULL DEFAULT FALSE,
    automute        BOOLEAN NOT NULL DEFAULT FALSE,
    visible         BOOLEAN NOT NULL DEFAULT TRUE,
    logged          BOOLEAN NOT NULL DEFAULT TRUE,
    active          BOOLEAN NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    deleted         BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT uq_channel_control_provenance UNIQUE (provenance_uri)
);

CREATE INDEX idx_channel_control_autojoin
    ON channel_control_options(autojoin) WHERE autojoin = TRUE AND deleted = FALSE;

CREATE TABLE message_log (
    id              UUID PRIMARY KEY,
    provenance_uri  VARCHAR(500) NOT NULL,
    direction       VARCHAR(20) NOT NULL,
    sender          VARCHAR(255) NOT NULL,
    content         TEXT NOT NULL,
    timestamp       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_message_log_channel_ts
    ON message_log(provenance_uri, timestamp DESC);
