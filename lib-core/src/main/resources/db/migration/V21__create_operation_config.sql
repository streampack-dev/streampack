CREATE TABLE operation_config (
    id                  UUID PRIMARY KEY,
    provenance_pattern  VARCHAR(500) NOT NULL DEFAULT '',
    operation_group     VARCHAR(100) NOT NULL,
    enabled             BOOLEAN NOT NULL DEFAULT TRUE,
    config              JSONB NOT NULL DEFAULT '{}',
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_operation_config UNIQUE (provenance_pattern, operation_group)
);

CREATE INDEX idx_operation_config_group
    ON operation_config(operation_group);
