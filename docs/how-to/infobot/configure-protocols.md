# Configure Infobot Protocols

The bundled infobot capabilities run inside `server-streampack`. Enable only the protocol adapters you intend to use.

Common toggles:

```dotenv
IRC_ENABLED=true
DISCORD_ENABLED=false
SLACK_ENABLED=false
CONSOLE_ENABLED=false
```

Discord also needs:

```dotenv
DISCORD_APPLICATION_ID=
DISCORD_PUBLIC_KEY=
DISCORD_BOT_TOKEN=
DISCORD_PERMISSIONS_VALUE=3072
```

Protocol identities map to Streampack users through service bindings. This lets one user have HTTP, IRC, Discord, and Slack identities while sharing the same role and permissions model.

For a concrete end-to-end bring-up, see [Run the infobot on IRC](../../tutorials/run-infobot-on-irc.md).
