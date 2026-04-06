CREATE TABLE karma_records (
    id           UUID PRIMARY KEY,
    subject      VARCHAR(200) NOT NULL,
    record_date  DATE NOT NULL,
    delta        INT NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX idx_karma_records_subject_date
    ON karma_records(subject, record_date);

CREATE INDEX idx_karma_records_subject
    ON karma_records(subject);
