# Run the Infobot on IRC

This tutorial brings the bundled infobot online on an IRC network using `server-streampack`: it
connects to a network, joins a channel, answers a command there, and comes back by itself after a
restart.

## 1. Enable IRC in the Runtime Environment

Set the IRC toggle in your runtime `.env`:

```dotenv
IRC_ENABLED=true
```

If you are deploying in Docker and also using host-based services such as mail, make sure the other
required runtime variables are already configured as described in the deployment docs.

## 2. Start the Server

Run the bundled server and make sure it is healthy:

```bash
curl -fsS http://localhost:8080/features
```

The IRC adapter is loaded only when `IRC_ENABLED=true`. Networks and channels are not configured in
files: they're registered at runtime with `irc …` commands, and kept in the database.

## 3. Create or Identify an Operator Account

Use the existing authentication/admin flow to ensure you have a `SUPER_ADMIN` user. Every `irc`
command requires `SUPER_ADMIN`. Run them from an admin-capable entry point, such as the web console.

## 4. Connect to a Network

Register the network and connect to it:

```text
irc connect libera irc.libera.chat nevet
```

If the network requires SASL, give the account and password too:

```text
irc connect libera irc.libera.chat nevet my-account my-password
```

The credentials work right away, but they're stored as typed only until the next start. Then they
move to environment variables, and the server refuses to start, naming what it needs, until they're
set (see step 9).

Have the network connect whenever the server starts:

```text
irc autoconnect libera true
```

## 5. Join a Channel

Join the target channel, with its `#`:

```text
irc join libera #java
```

A channel joined this way is logged, listed in the public log browser, and not rejoined after a
restart. Make it rejoin whenever the network connects:

```text
irc autojoin libera #java true
```

## 6. Talk to the Bot

In the channel:

```text
!version
!calc 2+3
nevet: version
```

The bot answers a message that starts with the signal character (`!`) or its nick and a colon or
comma. In a private message everything is addressed to it, so `version` alone works. If those work,
the IRC ingress path, the operation pipeline, and the IRC egress path are all functioning.

## 7. Configure Channel Behavior

The channel's settings, with what step 5 gave it:

```text
irc logged libera #java true
irc visible libera #java true
irc allow-ops libera #java false
```

- `logged` controls whether channel traffic is kept in the message log, which log search, the web
  log views and operations such as `ask` read.
- `visible` controls whether the logged channel is listed in the public log browser. Admins see it
  either way.
- `allow-ops` controls whether the bot keeps operator status. Off, it takes `+o` off itself again.

If the bot should stay in a channel but not speak, mute it:

```text
irc mute libera #java
```

A muted bot still listens, runs what's asked and logs; only its replies are held back.
`irc unmute libera #java` lets it speak again, and the setting survives restarts.

To use something other than `!` on one network:

```text
irc signal libera ~
```

`irc signal libera` alone goes back to the default.

## 8. Link Real Users to IRC Identities

To map IRC users to internal Streampack users and roles, link their IRC identities. Inspect the
expected form first:

```text
link help irc
```

An IRC identity is the network's name and the user's `ident@host`, with no leading `~` on the
ident. For someone on Libera whose hostmask is `alice!~alice@user/alice`:

```text
link user alice irc libera alice@user/alice
```

From then on, what they say on that network is theirs, with their role.

## 9. Restart With Credentials, and Check

If you connected with SASL, the next start stops and names two variables. For a network named
`libera`:

```dotenv
IRC_LIBERA_SASL_ACCOUNT=my-account
IRC_LIBERA_SASL_PASSWORD=my-password
```

Set them and start again; the stored credentials are now `env://` references to them. (For local
development, `STREAMPACK_SECURITY_ENFORCE_EXTERNAL_SECRETS=false` keeps literal credentials.)

After the start, check the state:

```text
irc status
irc status libera
```

The network should be connected and `#java` joined, without any command from you.

## Troubleshooting

- If the bot joins but never responds, check that the message is addressed (the signal character
  for that network, or the bot's nick) and that the channel isn't muted: `irc unmute libera #java`
  does no harm if it wasn't.
- If a channel isn't rejoined after a restart, turn on `irc autojoin` for it. If the network doesn't
  connect at all, turn on `irc autoconnect`.
- If the bot loses operator status unexpectedly, that is `allow-ops=false`, which de-ops the bot on
  purpose.
- If the server won't start after `irc connect` with SASL, it is waiting for the
  `IRC_<NETWORK>_SASL_*` variables it names.

For the full command list and the adapter's behavior, see [service-irc](../../service-irc/README.md).
