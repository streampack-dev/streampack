# service-irc

`service-irc` connects Streampack to IRC networks: it keeps the networks and channels the bot uses,
holds one live connection per connected network, turns what's said into messages for the operations,
and sends their replies back. Everything is configured at runtime with `irc …` commands, by a
`SUPER_ADMIN`; nothing about networks or channels lives in configuration files.

It's off unless `IRC_ENABLED=true`.

## Commands

All are `SUPER_ADMIN` only. `irc` alone lists them.

### Networks

| Command | What it does |
|---------|--------------|
| `irc connect <name> <host> <nick> [saslAccount] [saslPassword]` | Registers a network and connects to it. Given again with details, updates them (and reconnects). A network removed earlier is restored under its old row. |
| `irc connect <name>` | Connects a network already registered, with its stored details. |
| `irc disconnect <name>` | Disconnects; the network stays registered. |
| `irc remove <name>` | Removes the network and its channels (soft-deleted: `connect` with details brings the network back). |
| `irc autoconnect <name> <true\|false>` | Whether the network connects when Streampack starts. Off for a new or restored network. |
| `irc signal <name> [character]` | Sets the network's signal character (the `!` in `!help`), or with none, goes back to the default. Takes effect at once. |
| `irc status [name]` | One network's state and channels, or all networks'. |

### Channels

A channel is named with its `#`: `irc join libera #java`.

| Command | What it does |
|---------|--------------|
| `irc join <network> <#channel>` | Registers the channel (with default settings) and joins it. |
| `irc leave <network> <#channel>` | Leaves for now; the channel stays registered with its settings. |
| `irc autojoin <network> <#channel> <true\|false>` | Whether the channel is joined whenever the network connects. Off by default, so a channel joined once isn't rejoined after a restart until this is on. |
| `irc mute <network> <#channel>` / `irc unmute …` | The bot stops (or resumes) replying in the channel. It still listens, runs what's asked and logs; only its replies are held back. Kept across restarts. |
| `irc automute <network> <#channel> <true\|false>` | The same setting as `mute`/`unmute`, as a flag. |
| `irc logged <network> <#channel> <true\|false>` | Whether what's said in the channel is kept in the message log (for log search, the web log views, `ask`, `sentiment`, `!article`'s `logs`). On by default. |
| `irc visible <network> <#channel> <true\|false>` | Whether a logged channel is listed in the public log browser. On by default for a channel joined with `irc join`; turn it off to keep a channel's log out of public view (admins still see it). |
| `irc allow-ops <network> <#channel> <true\|false>` | Whether the bot may keep operator status in the channel. Off by default: given `+o`, the bot takes it off itself again (`MODE #channel -o nick`). |

Channel settings (`autojoin`, `mute`, `logged`, `visible`) are the shared channel options every
protocol uses, kept by the channel's provenance, `irc://<network>/%23<channel>`.

## Talking to the bot

- **In a channel**, a message is addressed to the bot when it starts with the signal character
  (`!help`) or the bot's nick and a colon or comma (`nevet: help`, `nevet, help`). The rest is
  passed on without that prefix. Messages not addressed to it still pass through, unaddressed, for
  the operations that listen to everything (karma, URL titles and the like), and are logged.
- **In a private message**, everything is addressed to the bot; a signal character or nick prefix
  is accepted and dropped. Private messages, and the bot's replies to them, are logged marked
  direct: kept, but never read back out of the log by anything.
- **Actions** (`/me waves`) are passed on as `* nick waves`.
- **Channel events** (joins, parts, quits, nick and topic changes) are logged, as from the nick
  they're about, but not passed to any operation.
- **Users** are recognized by their `ident@host` through the account links (`UserResolutionService`),
  so a reply knows who asked and what they may do.
- The bot answers CTCP `VERSION` with its identity (`IRC_IDENTITY`).

## Replies

- Each line of a reply is wrapped at 400 characters, and at most 4 lines are sent; when there was
  more, the last ends with ` [...more]`.
- A reply that would itself look like a command (starting with the signal character or the bot's
  nick) is dropped, so the bot never talks to itself.
- Replies go out through a queue that spaces messages to stay under networks' flood limits: the gap
  grows while a backlog builds and shrinks as it drains, between `IRC_MIN_SEND_DELAY_MS` and
  `IRC_MAX_SEND_DELAY_MS`.

## SASL credentials

Credentials given to `irc connect` are stored as typed, then, at the next start, moved to
environment variables: the stored values become `env://` references, and the start fails, naming
the variables needed (never their values), until they're set. For a network named `libera`:

```
IRC_LIBERA_SASL_ACCOUNT
IRC_LIBERA_SASL_PASSWORD
```

(The network's name, upper-cased, anything not a letter or digit as `_`.) Set
`STREAMPACK_SECURITY_ENFORCE_EXTERNAL_SECRETS=false` to keep literal credentials, as in development.

## Configuration

| Variable | Default | Meaning |
|----------|---------|---------|
| `IRC_ENABLED` | `false` | Turns IRC on. |
| `IRC_IDENTITY` | `Nevet IRC Bridge` | The bot's identity, as CTCP `VERSION` answers. |
| `IRC_ADAPTIVE_SEND_DELAY_ENABLED` | `true` | The adaptive send queue; off, every message waits `IRC_SEND_DELAY_MS`. |
| `IRC_MIN_SEND_DELAY_MS` / `IRC_MAX_SEND_DELAY_MS` | `120` / `1000` | The adaptive gap's bounds. |
| `IRC_SEND_DELAY_RAMP_UP_FACTOR` / `IRC_SEND_DELAY_RAMP_DOWN_FACTOR` | `1.1` / `0.9` | How fast the gap grows under a backlog and shrinks after. |
| `IRC_SEND_DELAY_MS` | `900` | The fixed gap when adaptive sending is off. |

The default signal character is `!` (`streampack.irc.signal-character`); `irc signal` overrides it
per network.

## Getting started

```
irc connect libera irc.libera.chat nevet nevet-account s3cret
irc autoconnect libera true
irc join libera #java
irc autojoin libera #java true
irc status libera
```

Then restart once with `IRC_LIBERA_SASL_ACCOUNT` and `IRC_LIBERA_SASL_PASSWORD` set, as the start
asks.

## Inside

| Component | Role |
|-----------|------|
| `IrcAdminOperation` | The `irc …` commands. |
| `IrcService` | Networks and channels: registering, settings, status. |
| `IrcConnectionManager` | One live `IrcAdapter` per connected network; connects the autoconnect networks at start. |
| `IrcAdapter` | The Kitteh IRC client's listener: addressing, users, events, autojoin, de-opping, replies. |
| `IrcEgressSubscriber` | Delivers replies to the right network and channel, unless the channel is muted. |
| `AdaptiveDelaySender` | The send queue's adaptive gap. |
| `IrcIdentityProvider` | How an IRC identity is written when linking it to an account. |
| `IrcSecretRefStartupGuard` | Moves SASL credentials to environment variables and holds the start until they're set. |

Messages are handled on virtual threads, outside any database transaction, so whatever the adapter
reads from an entity must be loaded with it (as a channel's network now is).
