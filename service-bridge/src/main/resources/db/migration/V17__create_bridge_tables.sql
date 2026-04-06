CREATE TABLE bridge_pair (
    id UUID PRIMARY KEY,
    first_uri VARCHAR(500) NOT NULL,
    second_uri VARCHAR(500) NOT NULL,
    copy_first_to_second BOOLEAN NOT NULL DEFAULT FALSE,
    copy_second_to_first BOOLEAN NOT NULL DEFAULT FALSE,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX idx_bridge_pair_first ON bridge_pair(first_uri) WHERE deleted = false;
CREATE UNIQUE INDEX idx_bridge_pair_second ON bridge_pair(second_uri) WHERE deleted = false;
