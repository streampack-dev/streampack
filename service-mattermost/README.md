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

Server tokens are stored as `SecretRef` values. A literal token given to `mattermost connect` is
rewritten on the next restart to `env://MATTERMOST_<NAME>_TOKEN`, and with
`STREAMPACK_SECURITY_ENFORCE_EXTERNAL_SECRETS` on, startup fails until that variable is present.
Identities map by Mattermost user id (`MattermostIdentityProvider`).

For a local development server, see
[Run a Local Mattermost for Development](../docs/how-to/develop/run-local-mattermost.md).

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

- `join` adds the account to public channels through the API; the account must be added to private
  channels on Mattermost by an admin. `leave` removes the account, so the server stops sending posts.
- Private channels and direct messages register hidden and unlogged; public channels register
  visible and logged. `logged=false` stops capture, not just browsing.
- Channel routing uses the Mattermost channel ID. Names repeat across teams, so a name registered
  on two teams must be addressed by id.
- `mattermost connect … <token>` is redacted in the message log; `env://MATTERMOST_<NAME>_TOKEN` is
  accepted in place of a literal token. A stored literal is rewritten to that reference only once
  the variable exists; the startup guard never prints token values.
- `autojoin` channels are joined on every connect and reconnect.
- A dropped socket reconnects with doubling delays up to five minutes; `status` reports
  `reconnecting` until it does.
- This first pass does not implement slash commands or outgoing webhooks.
