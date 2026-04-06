CREATE TABLE ignored_hosts (
    id        UUID PRIMARY KEY,
    host_name VARCHAR(255) NOT NULL
);

CREATE UNIQUE INDEX idx_ignored_hosts_host_name ON ignored_hosts(host_name);
