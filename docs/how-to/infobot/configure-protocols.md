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

Mattermost needs no further environment at startup. Servers are registered at runtime by a super admin, with a regular account or bot account token that the server keeps as an `env://` reference:

```text
mattermost connect work https://mattermost.example.com <token>
mattermost channels work
mattermost join work town-square
mattermost autoconnect work true
```

The account must be a member of the team and of any private channel it should read. Streampack reads posts over the server's WebSocket API and replies over REST, so only outbound connectivity from Streampack to Mattermost is needed, which suits restricted networks. See [Run a local Mattermost for development](../develop/run-local-mattermost.md) for an end-to-end bring-up without a corporate instance.

Protocol identities map to Streampack users through service bindings. This lets one user have HTTP, IRC, Discord, Slack, and Mattermost identities while sharing the same role and permissions model.

For a concrete end-to-end bring-up, see [Run the infobot on IRC](../../tutorials/run-infobot-on-irc.md).
