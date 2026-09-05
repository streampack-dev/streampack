# service-mattermost

Mattermost protocol integration for Streampack.

This service provides:

- admin operations for configuring Mattermost servers and channels
- outbound replies via the Mattermost REST API
- inbound message intake via the Mattermost WebSocket API

## Configuration

Server-level enablement lives in `server-streampack`:

```yaml
streampack:
  mattermost:
    enabled: false
    signal-character: "!"
    reconnect-delay: PT15S
```

`enabled` controls whether the runtime connection infrastructure is started. The admin operations
and persistence layer still exist even when runtime connectivity is disabled.

## Admin Commands

These commands require `SUPER_ADMIN`:

```text
mattermost connect <name> [<base-url> <token>]
mattermost disconnect <name>
mattermost remove <name>
mattermost autoconnect <name> <true|false>
mattermost channels <server> [term]
mattermost join <server> <channel-id-or-name>
mattermost leave <server> <channel-id-or-name>
mattermost autojoin <server> <channel-id-or-name> <true|false>
mattermost mute <server> <channel-id-or-name>
mattermost unmute <server> <channel-id-or-name>
mattermost automute <server> <channel-id-or-name> <true|false>
mattermost visible <server> <channel-id-or-name> <true|false>
mattermost logged <server> <channel-id-or-name> <true|false>
mattermost signal <name> [character]
mattermost status [server]
```

## Operational Flow

Typical setup flow:

1. `mattermost connect work https://mattermost.example.com <token>`
2. `mattermost channels work general`
3. `mattermost join work town-square`
4. `mattermost autoconnect work true`

## Ingress Model

This implementation is WebSocket-first, not polling-based.

- REST API:
  - validate the token
  - discover channels
  - send replies
- WebSocket:
  - authenticate with `authentication_challenge`
  - listen for `posted` events
  - dispatch addressed messages into Streampack

Direct messages are treated as addressed automatically. Channel messages are addressed when they
use the configured signal character or mention the bot username directly.

## Constraints

- The bot must be a member of any private channel it should observe or post into.
- Channel routing uses the Mattermost channel ID even if registration starts from a name lookup.
- This first pass does not implement slash commands or outgoing webhooks.
