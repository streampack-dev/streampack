# Configure Infobot Protocols

The bundled infobot capabilities run inside `server-streampack`. Enable only the protocol adapters you intend to use.

Common toggles:

```dotenv
IRC_ENABLED=true
DISCORD_ENABLED=false
SLACK_ENABLED=false
MATTERMOST_ENABLED=false
CONSOLE_ENABLED=false
```

Discord also needs:

```dotenv
DISCORD_APPLICATION_ID=
DISCORD_PUBLIC_KEY=
DISCORD_BOT_TOKEN=
DISCORD_PERMISSIONS_VALUE=3072
```

IRC, Slack and Mattermost need nothing further at startup. Their networks, workspaces and servers are registered at runtime by a super admin, and any credentials given then are moved to environment variables at the next start: the start fails, naming the variables it needs (never their values), until they're set, and the stored credentials become `env://` references.

## IRC

```text
irc connect libera irc.libera.chat nevet [sasl-account sasl-password]
irc autoconnect libera true
irc join libera #java
irc autojoin libera #java true
```

SASL credentials move to `IRC_LIBERA_SASL_ACCOUNT` and `IRC_LIBERA_SASL_PASSWORD`. See [Run the infobot on IRC](../../tutorials/run-infobot-on-irc.md) for a walk-through, and [service-irc](../../../service-irc/README.md) for the details.

## Slack

The Slack adapter uses Socket Mode, so like Mattermost it needs only outbound connectivity. Create a Slack app with Socket Mode on, and give Streampack its bot token (`xoxb-…`) and app-level token (`xapp-…`):

```text
slack connect jvm-news xoxb-... xapp-...
slack autoconnect jvm-news true
slack join jvm-news #java
slack autojoin jvm-news #java true
```

The tokens move to `SLACK_JVM_NEWS_BOT_TOKEN` and `SLACK_JVM_NEWS_APP_TOKEN`. The bot hears only conversations it's a member of: `slack join` puts it into a public channel, and `autojoin` brings it back on each connect; a private channel needs the bot invited in Slack. See [service-slack](../../../service-slack/README.md) for the app's scopes and events.

## Mattermost

Servers are registered with a regular account or bot account token:

```text
mattermost connect work https://mattermost.example.com <token>
mattermost channels work
mattermost join work town-square
mattermost autoconnect work true
```

The token moves to `MATTERMOST_WORK_TOKEN`. The account must be a member of the team and of any private channel it should read. Streampack reads posts over the server's WebSocket API and replies over REST, so only outbound connectivity from Streampack to Mattermost is needed, which suits restricted networks. See [Run a local Mattermost for development](../develop/run-local-mattermost.md) for an end-to-end bring-up without a corporate instance, and [service-mattermost](../../../service-mattermost/README.md) for the details.

## Identities

Protocol identities map to Streampack users through service bindings (`link user …`; `link help <protocol>` shows each protocol's form). This lets one user have HTTP, IRC, Discord, Slack, and Mattermost identities while sharing the same role and permissions model.

For local development, `STREAMPACK_SECURITY_ENFORCE_EXTERNAL_SECRETS=false` keeps credentials as typed.
