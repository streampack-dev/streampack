-- Migrate channel flags to shared table
INSERT INTO channel_control_options
    (id, provenance_uri, autojoin, automute, visible, logged, active, created_at, updated_at, deleted)
SELECT gen_random_uuid(),
       'irc://' || n.name || '/' || replace(c.name, '#', '%23'),
       c.autojoin, c.automute, c.visible, c.logged, TRUE,
       c.created_at, c.updated_at, c.deleted
FROM irc_channels c
JOIN irc_networks n ON c.network_id = n.id;

-- Drop migrated columns
ALTER TABLE irc_channels DROP COLUMN autojoin;
ALTER TABLE irc_channels DROP COLUMN automute;
ALTER TABLE irc_channels DROP COLUMN visible;
ALTER TABLE irc_channels DROP COLUMN logged;

-- Drop IRC-specific message log (replaced by protocol-agnostic message_log)
DROP TABLE IF EXISTS irc_messages;
