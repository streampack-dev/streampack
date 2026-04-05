CREATE TABLE provenance_state (
    id              UUID PRIMARY KEY,
    provenance_uri  VARCHAR(500) NOT NULL,
    key             VARCHAR(100) NOT NULL,
    data            JSONB NOT NULL DEFAULT '{}',
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_provenance_state UNIQUE (provenance_uri, key)
);
