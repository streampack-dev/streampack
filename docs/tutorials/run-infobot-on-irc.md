# Run the Infobot on IRC

This tutorial brings the bundled infobot online on an IRC network using `server-streampack`.

## 1. Enable IRC in the Runtime Environment

Set the IRC toggle in your runtime `.env`:

```dotenv
IRC_ENABLED=true
```

If you are deploying in Docker and also using host-based services such as mail, make sure the other required runtime variables are already configured as described in the deployment docs.

## 2. Start the Server

Run the bundled server and make sure it is healthy:

```bash
curl -fsS http://localhost:8080/features
```

The IRC adapter is loaded only when `IRC_ENABLED=true`.

## 3. Create or Identify an Operator Account

Use the existing authentication/admin flow to ensure you have a `SUPER_ADMIN` user. IRC network and channel management is exposed through `IrcAdminOperation`, which requires `SUPER_ADMIN`.

## 4. Connect to a Network

From a console/admin-capable entry point, register and connect the IRC network:

```text
irc connect libera irc.libera.chat nevet
```

If the network requires SASL:

```text
irc connect libera irc.libera.chat nevet my-account my-password
```

This creates or updates the stored `IrcNetwork` entity and asks the runtime connection manager to connect.

## 5. Join a Channel

Join the target channel:

```text
irc join libera #java
```

Make it autojoin on reconnect/startup:

```text
irc autojoin libera #java true
```

If the bot should remain present but not speak, mute it:

```text
irc mute libera #java
```

## 6. Configure Basic Channel Behavior

Common follow-up settings:

```text
irc logged libera #java true
irc visible libera #java true
irc allow-ops libera #java false
irc signal libera !
```

- `logged` controls whether channel traffic is captured in message logs.
- `visible` controls whether higher-level features should treat the channel as visible.
- `allow-ops` controls whether the bot keeps operator status.
- `signal` overrides the command prefix for one network.

## 7. Link Real Users to IRC Identities

If you want IRC users to map to internal Streampack users and roles, link their IRC identities through the core linking operations. Inspect the expected syntax first:

```text
link help irc
```

Then create a binding:

```text
link user alice irc libera alice@example.com
```

The exact external identifier should match the identity form described by the IRC identity provider in your deployment.

## 8. Verify End-to-End Behavior

In the channel:

```text
!version
!calc 2+3
```

If those work, the IRC ingress path, operation pipeline, and IRC egress path are all functioning.

## 9. Inspect Status and Persist Connectivity

Check current runtime/configured state:

```text
irc status
irc status libera
```

If this network should reconnect automatically on server startup:

```text
irc autoconnect libera true
```

## Troubleshooting

- If `!version` or other commands fail with lazy proxy/session errors, inspect the user-resolution path first. IRC resolves bound users before dispatching any addressed command.
- If the bot joins but never responds, check the signal character and whether the message is being addressed.
- If the bot loses operator status unexpectedly, that is usually `allow-ops=false`, which intentionally auto-deops the bot.
