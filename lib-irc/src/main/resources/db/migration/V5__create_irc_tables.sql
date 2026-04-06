CREATE TABLE irc_networks (
    id              UUID PRIMARY KEY,
    name            VARCHAR(100) NOT NULL,
    host            VARCHAR(255) NOT NULL,
    port            INT NOT NULL DEFAULT 6697,
    tls             BOOLEAN NOT NULL DEFAULT TRUE,
    nick            VARCHAR(50) NOT NULL,
    sasl_account    VARCHAR(100),
    sasl_password   VARCHAR(255),
    autoconnect     BOOLEAN NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    deleted         BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT uq_irc_network_name UNIQUE (name)
);

CREATE TABLE irc_channels (
    id              UUID PRIMARY KEY,
    network_id      UUID NOT NULL REFERENCES irc_networks(id),
    name            VARCHAR(200) NOT NULL,
    autojoin        BOOLEAN NOT NULL DEFAULT FALSE,
    automute        BOOLEAN NOT NULL DEFAULT FALSE,
    visible         BOOLEAN NOT NULL DEFAULT TRUE,
    logged          BOOLEAN NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    deleted         BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE UNIQUE INDEX idx_irc_channels_network_name
    ON irc_channels(network_id, name) WHERE deleted = FALSE;

CREATE TABLE irc_messages (
    id              UUID PRIMARY KEY,
    channel_id      UUID NOT NULL REFERENCES irc_channels(id),
    nick            VARCHAR(50) NOT NULL,
    content         TEXT NOT NULL,
    message_type    VARCHAR(50) NOT NULL,
    timestamp       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_irc_messages_channel_ts
    ON irc_messages(channel_id, timestamp DESC);

CREATE INDEX idx_irc_networks_autoconnect
    ON irc_networks(autoconnect) WHERE autoconnect = TRUE AND deleted = FALSE;

CREATE INDEX idx_irc_channels_autojoin
    ON irc_channels(network_id, autojoin) WHERE autojoin = TRUE AND deleted = FALSE;
