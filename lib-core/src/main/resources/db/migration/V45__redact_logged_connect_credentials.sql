-- Connect commands logged before their tokens were redacted (issue #17 review).
-- Token positions mirror the operations' redaction rules; whitespace runs are tolerated.

-- slack connect <name> <bot-token> <app-token>
UPDATE message_log
SET content = regexp_replace(
        content,
        '^(\s*slack\s+connect\s+\S+\s+)\S+(\s+)\S+',
        '\1[REDACTED]\2[REDACTED]',
        'i')
WHERE content ~* '^\s*slack\s+connect\s+\S+\s+\S+\s+\S+';

-- mattermost connect <name> <base-url> <token>
UPDATE message_log
SET content = regexp_replace(
        content,
        '^(\s*mattermost\s+connect\s+\S+\s+\S+\s+)\S+',
        '\1[REDACTED]',
        'i')
WHERE content ~* '^\s*mattermost\s+connect\s+\S+\s+\S+\s+\S+';

-- irc connect <name> <host> <nick> <sasl-account> <sasl-password> (positions 5 and 6)
UPDATE message_log
SET content = regexp_replace(
        content,
        '^(\s*irc\s+connect\s+\S+\s+\S+\s+\S+\s+)\S+(\s+)\S+',
        '\1[REDACTED]\2[REDACTED]',
        'i')
WHERE content ~* '^\s*irc\s+connect\s+\S+\s+\S+\s+\S+\s+\S+\s+\S+';
