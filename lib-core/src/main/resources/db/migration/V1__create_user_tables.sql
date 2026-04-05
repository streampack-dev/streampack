-- User accounts
CREATE TABLE users (
    id              UUID PRIMARY KEY,
    username        VARCHAR(255) NOT NULL UNIQUE,
    email           VARCHAR(255) NOT NULL,
    email_verified  BOOLEAN NOT NULL DEFAULT FALSE,
    display_name    VARCHAR(255) NOT NULL,
    real_name       VARCHAR(255),
    role            VARCHAR(50) NOT NULL DEFAULT 'USER',
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    last_login_at   TIMESTAMP WITH TIME ZONE,
    deleted         BOOLEAN NOT NULL DEFAULT FALSE
);

-- Protocol-specific identity bindings
CREATE TABLE service_bindings (
    id                      UUID PRIMARY KEY,
    user_id                 UUID NOT NULL REFERENCES users(id),
    protocol                VARCHAR(50) NOT NULL,
    service_id              VARCHAR(255) NOT NULL,
    external_identifier     VARCHAR(255) NOT NULL,
    metadata                JSONB NOT NULL DEFAULT '{}',
    CONSTRAINT uq_service_binding UNIQUE (protocol, service_id, external_identifier)
);

CREATE INDEX idx_service_bindings_user_id ON service_bindings(user_id);
CREATE INDEX idx_users_username ON users(username);
CREATE INDEX idx_users_email ON users(email);
CREATE INDEX idx_users_active ON users(deleted) WHERE deleted = FALSE;
