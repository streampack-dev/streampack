CREATE TABLE temperature_buckets (
    id              UUID PRIMARY KEY,
    namespace       VARCHAR(120) NOT NULL,
    subject_key     VARCHAR(512) NOT NULL,
    signal          VARCHAR(120) NOT NULL,
    bucket_date     DATE NOT NULL,
    positive_delta  BIGINT NOT NULL DEFAULT 0,
    negative_delta  BIGINT NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX idx_temperature_buckets_identity
    ON temperature_buckets(namespace, subject_key, signal, bucket_date);

CREATE INDEX idx_temperature_buckets_subject
    ON temperature_buckets(namespace, signal, subject_key);

CREATE INDEX idx_temperature_buckets_date
    ON temperature_buckets(bucket_date);
