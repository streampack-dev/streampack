-- Redact SASL credentials from historical irc connect commands in the message log.
-- Tokens at positions 5 and 6 (0-indexed) contain saslAccount and saslPassword.
-- Example: "irc connect libera irc.libera.chat nevet myaccount mypassword"
-- becomes: "irc connect libera irc.libera.chat nevet [REDACTED] [REDACTED]"

UPDATE message_log
SET content = (
    SELECT array_to_string(
        array_agg(
            CASE
                WHEN ordinality IN (6, 7) THEN '[REDACTED]'
                ELSE token
            END
            ORDER BY ordinality
        ),
        ' '
    )
    FROM unnest(regexp_split_to_array(content, '\s+')) WITH ORDINALITY AS t(token, ordinality)
)
WHERE direction = 'INBOUND'
  AND lower(content) LIKE 'irc connect %'
  AND array_length(regexp_split_to_array(content, '\s+'), 1) >= 6;
