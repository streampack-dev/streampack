CREATE TABLE factoids (
    id          UUID PRIMARY KEY,
    selector    VARCHAR(200) NOT NULL,
    locked      BOOLEAN NOT NULL DEFAULT FALSE,
    updated_by  VARCHAR(100),
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_factoid_selector UNIQUE (selector)
);

CREATE TABLE factoid_attributes (
    id              UUID PRIMARY KEY,
    factoid_id      UUID NOT NULL REFERENCES factoids(id) ON DELETE CASCADE,
    attribute_type  VARCHAR(50) NOT NULL,
    attribute_value VARCHAR(512),
    updated_by      VARCHAR(100),
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX idx_factoid_attr_type
    ON factoid_attributes(factoid_id, attribute_type);

CREATE INDEX idx_factoid_selector
    ON factoids(selector);
