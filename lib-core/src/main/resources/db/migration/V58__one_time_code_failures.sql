-- Wrong guesses at a recipient's one-time codes (#144). Once a recipient misses too often, every
-- active code it holds is expired and this row is cleared; a quiet spell longer than a code's
-- lifetime also starts the count over.
CREATE TABLE one_time_code_failures (
    channel          VARCHAR(20)  NOT NULL,
    recipient        VARCHAR(255) NOT NULL,
    failures         INTEGER      NOT NULL,
    last_failure_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (channel, recipient)
);
