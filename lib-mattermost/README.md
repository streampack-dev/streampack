# lib-mattermost

Persistent Mattermost configuration for Streampack.

This module owns the database state for Mattermost integration:

- `MattermostServer`
  - logical server name
  - base URL
  - token reference
  - optional per-server signal override
  - autoconnect flag
- `MattermostChannel`
  - registered channel name
  - stable Mattermost channel ID
  - optional team ID
  - channel type

## Purpose

`lib-mattermost` is intentionally small. It does not know how to connect to Mattermost or process
messages. It only defines the persisted state that `service-mattermost` uses.

## Migration

`V37__create_mattermost_tables.sql` creates:

- `mattermost_servers`
- `mattermost_channels`

The channel table stores the stable channel ID because routing should target IDs, not human-readable
names.
