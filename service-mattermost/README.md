# service-mattermost

`service-mattermost` connects Streampack to Mattermost servers: it keeps the servers and channels the
bot uses, holds one live connection per connected server, turns posts into messages for the
operations, and sends their replies back. The bot is an ordinary Mattermost account (a user or bot
account with a personal access token): it reads posts over the server's WebSocket API and does
everything else (channel lookup, joining, replies) over the REST API. Everything is configured at
runtime with `mattermost …` commands, by a `SUPER_ADMIN`; nothing about servers or channels lives in
configuration files.

It's off unless `MATTERMOST_ENABLED=true`. With it off, the commands still exist and still edit the
stored servers and channels, but nothing connects: `connect` answers `Connecting to '<name>'...` and
does nothing more, `channels` says the server isn't connected, and `status` lists the stored servers.

## Commands

All are `SUPER_ADMIN` only. `mattermost` alone lists them.

### Servers

| Command | What it does |
|---------|--------------|
| `mattermost connect <name> <base-url> <token>` | Registers a server and connects to it. Given again with details, updates them (and reconnects). A server removed earlier is restored under its old row, with `autoconnect` off. The token may be `env://SOME_VARIABLE` instead of a literal. |
| `mattermost connect <name>` | Connects a server already registered, with its stored details. Does nothing if it already has a connection (live or retrying). |
| `mattermost disconnect <name>` | Disconnects and stops any reconnect attempts; the server stays registered. |
| `mattermost remove <name>` | Disconnects, and removes the server and its channels (soft-deleted: `connect` with details brings the server back, but not its channels). |
| `mattermost autoconnect <name> <true\|false>` | Whether the server connects when Streampack starts. Off for a new or restored server. |
| `mattermost signal <name> [character]` | Sets the server's signal character (the `!` in `!help`), or with none, goes back to the default (`MATTERMOST_SIGNAL`). Takes effect at once. |
| `mattermost channels <name> [term]` | Lists channels as `display name [id] on team`, across every team the account belongs to. With no term, the channels the account is a member of; with a term, the results of Mattermost's channel search. Needs a live connection. |
| `mattermost status [name]` | With a name, `connected` or `not connected`. Without, every server that has a connection, each `connected` or `reconnecting`. With Mattermost off, the stored servers instead. |

### Channels

A channel is named by its Mattermost id (lower-case letters and digits, 26 of them) or by its name (the
URL name, such as `town-square`); a leading `#` is ignored. Names repeat across teams (every team
has a `town-square`), so a name that matches more than one channel is refused with the candidate
ids, and the id must be used.

| Command | What it does |
|---------|--------------|
| `mattermost join <server> <channel>` | Registers the channel (with default settings, below) and, for a public channel, adds the account to it. A private channel, direct or group message is registered, but the account has to be added to it on Mattermost. |
| `mattermost leave <server> <channel>` | Removes the account from the channel on Mattermost, so the server stops sending its posts, and retires the channel's record. Its settings are kept, and come back with a later `join`. |
| `mattermost autojoin <server> <channel> <true\|false>` | Whether the account is added to the channel each time the server connects or reconnects. Off by default. The account can add itself only to public channels; for a private channel it hasn't been added to on Mattermost, the attempt fails and a warning is logged. |
| `mattermost mute <server> <channel>` / `mattermost unmute …` | The bot stops (or resumes) replying in the channel. It still listens, runs what's asked and logs; only its replies are held back (they're still written to the log when the channel is logged). Kept across restarts. |
| `mattermost automute <server> <channel> <true\|false>` | The same setting as `mute`/`unmute`, as a flag. |
| `mattermost logged <server> <channel> <true\|false>` | Whether what's said in the channel, and the bot's replies, are kept in the message log (for log search, the web log views, and anything else that reads the log). With it off, nothing from the channel is written. |
| `mattermost visible <server> <channel> <true\|false>` | Whether a logged channel is listed in the public log browser. Admins see every logged channel regardless. |

How a channel is found:

- **`join`**, while connected, asks Mattermost: an id is looked up directly; otherwise the name is
  searched on each of the account's teams, and an exact match on the name or display name wins. If
  there is no exact match but the search found exactly one channel, that one is used. While not
  connected, `join` takes only an id, and registers it without touching membership.
- **Every other channel command** works only on a channel already registered with `join`, found by
  id, else by name among the registered channels.

Defaults at `join`: a public channel starts logged and visible; a private channel, direct or group
message starts unlogged and hidden, and has to be turned on with `logged` and `visible`. The type
comes from Mattermost, so a channel joined by id while not connected is treated as public (logged
and visible). The defaults apply only when the channel's settings are first created; joining again
doesn't reset them.

Channel settings (`autojoin`, `mute`, `logged`, `visible`) are the shared channel options every
protocol uses, kept by the channel's provenance, `mattermost://<server>/<channel-id>`.

## Talking to the bot

The bot receives every post in every channel its account belongs to, whether or not the channel is
registered here.

- **In a channel**, a post is addressed to the bot when it starts with the signal character
  (`!help`) or with an @-mention of the account (`@nevet help`, `@nevet: help`, `@nevet, help`).
  The rest is passed on without that prefix. A mention anywhere but the start doesn't count. Posts
  not addressed to it still pass through, unaddressed, for the operations that listen to everything
  (karma, URL titles and the like).
- **In a direct message**, everything is addressed to the bot; a signal character or mention prefix
  is accepted and dropped. Group messages are treated like channels.
- **Ignored:** the bot's own posts, system posts (joins, header changes and the like), posts with
  no text (a file alone), and edits. A post delivered twice is handled once.
- **Users** are recognized by their Mattermost user id through the account links
  (`MattermostIdentityProvider`, `UserResolutionService`), so a reply knows who asked and what they
  may do. Someone without a linked account is anonymous.
- **Logging:** a channel that was never registered has no settings, and is logged, but isn't listed
  in the log browser. That includes direct messages, unless they're registered with `join` by id.

## Replies

- A reply is posted to the channel (or direct message) the request came from, as a new post, not a
  thread reply. An error is sent as `Error: …`.
- Nothing shortens or splits a reply. A post Mattermost refuses (too long, say) is logged and
  dropped.
- A reply that would itself look like a command (starting with the signal character, or with
  `@<bot account> `) is dropped, so the bot never talks to itself.
- Replies to a muted channel are dropped.

## Sign-in codes

With Mattermost on, the site can send one-time sign-in codes as a direct message from the bot to a
username on a connected server. A username the server doesn't know, or a server that isn't connected,
gets nothing. See [Run a Local Mattermost for
Development](../docs/how-to/develop/run-local-mattermost.md) for trying it.

## Tokens

The token given to `mattermost connect` is stored as typed, then, at the next start, moved to an
environment variable: the start fails, naming the variable (never its value), until it's set; once
it is, the stored value becomes an `env://` reference. For a server named `work`:

```
MATTERMOST_WORK_TOKEN
```

(The server's name, upper-cased, anything not a letter or digit as `_`.) A token given as
`env://…` is checked the same way: the start fails until that variable is set. The token is
redacted from the message log when `connect` is typed in a chat.

The check runs only when `MATTERMOST_ENABLED=true`. Set
`STREAMPACK_SECURITY_ENFORCE_EXTERNAL_SECRETS=false` to skip it and keep a literal token, as in
development.

## Configuration

| Variable | Default | Meaning |
|----------|---------|---------|
| `MATTERMOST_ENABLED` | `false` | Turns Mattermost on. |
| `MATTERMOST_SIGNAL` | `!` | The default signal character; `mattermost signal` overrides it per server. |
| `MATTERMOST_RECONNECT_DELAY` | `PT15S` | The first wait before reconnecting after a dropped socket or a failed connect. It doubles with each failed attempt, up to five minutes, and starts over once connected. `PT0S` turns reconnecting off. |
| `MATTERMOST_<NAME>_TOKEN` | none | The token for the server registered as `<name>` (see Tokens). |

These map to `streampack.mattermost.enabled`, `signal-character` and `reconnect-delay` in
`server-streampack`'s `application.yml`.

## Getting started

Make an account for the bot on Mattermost, give it a personal access token, and add it to the team.
Then:

```
mattermost connect work https://mattermost.example.com <token>
mattermost channels work
mattermost join work town-square
mattermost autojoin work town-square true
mattermost autoconnect work true
mattermost status work
```

Then restart once with `MATTERMOST_WORK_TOKEN` set, as the start asks. For a private channel, add
the account to it on Mattermost, `join` it by id, and turn on `logged` and `visible` if its log
should be kept and shown. For a server on your own machine, see [Run a Local Mattermost for
Development](../docs/how-to/develop/run-local-mattermost.md).

## Inside

| Component | Role |
|-----------|------|
| `MattermostAdminOperation` | The `mattermost …` commands. |
| `MattermostService` | Servers and channels: registering, finding a channel, settings, status. |
| `MattermostConnectionManager` | One live `MattermostAdapter` per connected server; connects the autoconnect servers at start, joins autojoin channels after each connect. |
| `MattermostAdapter` | The WebSocket listener and REST client: addressing, users, joining and leaving, replies, reconnecting. |
| `ReconnectBackoff` | The doubling reconnect delay. |
| `MattermostEgressSubscriber` | Delivers replies to the right server and channel, unless the channel is muted or the reply would loop. |
| `MattermostIdentityProvider` | How a Mattermost identity (server and user id) is checked when linking it to an account. |
| `MattermostCodeDelivery` | Sends sign-in codes as direct messages. |
| `MattermostSecretRefStartupGuard` | Moves tokens to environment variables and holds the start until they're set. |
| `MattermostServer`, `MattermostChannel` (`lib-mattermost`) | The stored servers and channels. |

None of this runs inside a database transaction (the autojoin pass, for one, runs on its own thread
after each connect), so whatever is read from an entity must be loaded with it, as a channel's
server now is.
