# service-slack

`service-slack` connects Streampack to Slack workspaces over Socket Mode: it keeps the workspaces
and channels the bot uses, holds one live connection per connected workspace, turns what's said into
messages for the operations, and posts their replies back. Everything is configured at runtime with
`slack …` commands, by a `SUPER_ADMIN`; nothing about workspaces or channels lives in configuration
files.

It's off unless `SLACK_ENABLED=true`. While it's off, the `slack …` commands still work on the
stored records, but nothing connects.

## Commands

All are `SUPER_ADMIN` only. `slack` alone lists them.

### Workspaces

| Command | What it does |
|---------|--------------|
| `slack connect <name> <bot-token> <app-token>` | Registers a workspace and connects to it. Given again for a registered workspace, replaces both tokens, disconnects and reconnects. A workspace removed earlier is restored under its old row, with autoconnect off. The tokens are redacted from the message log. |
| `slack connect <name>` | Connects a workspace already registered, with its stored tokens. Does nothing (but still answers "Connecting…") if it's already connected. |
| `slack disconnect <name>` | Disconnects; the workspace stays registered. |
| `slack remove <name>` | Disconnects, then removes the workspace and its channels (soft-deleted). `connect` with tokens brings the workspace back, but not its channels: join them again. |
| `slack autoconnect <name> <true\|false>` | Whether the workspace connects when Streampack starts. Off for a new or restored workspace. |
| `slack signal <name> [signal]` | Sets the workspace's signal (the `!` in `!help`; up to 10 characters), or with none, goes back to the default (`SLACK_SIGNAL`). Takes effect at once. |
| `slack status [name]` | With Slack on: whether one workspace, or which workspaces, are connected. With Slack off: the stored workspaces, autoconnect or manual, and one workspace's channel names. |

The bot token is the `xoxb-…` one, the app token the `xapp-…` one Socket Mode needs. Either may be
given as `env://VARIABLE` instead of a value, and is then read from that variable when connecting.

### Channels

A channel is named by its name, with or without its `#` (`#java`), or by its Slack id
(`C0123ABCD`).

| Command | What it does |
|---------|--------------|
| `slack join <workspace> <#channel\|id>` | Connected: finds the channel in Slack, registers it with its id, and puts the bot in it if it's public. A private channel is registered, but the bot has to be invited to it in Slack (`/invite @nevet`) first, or Slack won't show it. Not connected: takes only an id, and registers the channel to be joined on connect. Joining a channel again refreshes its name and id. |
| `slack leave <workspace> <#channel\|id>` | Takes the bot out of the channel (connected only); the channel stays registered with its settings. |
| `slack autojoin <workspace> <#channel\|id> <true\|false>` | Whether the bot joins the channel each time the workspace connects. Off by default. Public channels only; for a private one, the invitation is what keeps the bot in. |
| `slack mute <workspace> <#channel\|id>` / `slack unmute …` | The bot stops (or resumes) replying in the channel. It still listens, runs what's asked and logs; only its replies are held back. Kept across restarts. |
| `slack automute <workspace> <#channel\|id> <true\|false>` | The same setting as `mute`/`unmute`, as a flag. |
| `slack logged <workspace> <#channel\|id> <true\|false>` | Whether what's said in the channel, and the bot's replies, are kept in the message log. On by default for a public channel, off for a private one. |
| `slack visible <workspace> <#channel\|id> <true\|false>` | Whether a logged channel is listed in the public log browser (admins see logged channels either way). On by default for a public channel, off for a private one. |

Channel settings (`autojoin`, `mute`, `logged`, `visible`) are the shared channel options every
protocol uses, kept by the channel's provenance, `slack://<workspace>/<channel-id>`: the address
its messages arrive with. Every command but `join` needs a channel registered with its id; a
channel registered before ids were kept answers with an error until it's joined again while
connected, which finds its id and moves nothing else.

## Talking to the bot

The bot hears only the conversations Slack sends it events for: channels it's a member of, and its
DMs.

- **In a channel** (or a group DM), a message is addressed to the bot when it starts with the signal
  (`!help`) or a mention of the bot (`@nevet help`). The rest is passed on without that prefix. A
  signal or mention with nothing after it isn't addressed. Messages not addressed to it still pass
  through, unaddressed, for the operations that listen to everything, and are logged.
- **In a DM**, everything is addressed to the bot and passed on as written. DMs and group DMs are
  logged marked direct: kept, but never read back out of the log by anything.
- **Actions** (`/me waves`) are passed on as `* name waves`, unaddressed.
- **Reactions** to the latest message the bot saw in a channel are passed on, unaddressed, as
  `* name reacted with :emoji:`, at most 5 per message. Reactions to older messages, and in DMs,
  are ignored.
- **Ignored:** messages from any bot or integration (including this one), and message subtypes —
  edits, deletions, joins, file shares and the like. Joins, leaves and topic changes are not logged.
- **Users** are recognized by their Slack user id through the account links
  (`UserResolutionService`; an identity is a workspace name and a user id), so a reply knows who
  asked and what they may do. The name shown in logs is the Slack display name, else the real name,
  else the username, looked up once per connection.

## Replies

- A reply goes to the conversation it answers: the channel, or the user's DM. It's posted to the
  channel itself, not into a thread.
- The text is sent whole: the adapter doesn't wrap, split or cut it. An error result is sent as
  `Error: …`.
- A reply that would itself look like a command (starting with the signal or a mention of the bot)
  is dropped, so the bot never talks to itself.
- A reply for a workspace that isn't connected is dropped.

## Tokens

Tokens given to `slack connect` are stored as typed. At each start (with Slack on), any token stored
as a literal is moved to an environment variable: if the variable is set, the stored value becomes
an `env://` reference to it; if not, the start fails, naming the variables needed (never their
values), until they're set. A stored `env://` reference whose variable is missing also fails the
start. For a workspace named `jvm-news`:

```
SLACK_JVM_NEWS_BOT_TOKEN
SLACK_JVM_NEWS_APP_TOKEN
```

(The workspace's name, upper-cased, each run of anything not a letter or digit as one `_`.) Once a
token lives in a variable, change it there: new tokens given to `slack connect` are used until the
next start, which replaces them with the variable's value. Set
`STREAMPACK_SECURITY_ENFORCE_EXTERNAL_SECRETS=false` to keep literal tokens, as in development.

## Configuration

| Variable | Default | Meaning |
|----------|---------|---------|
| `SLACK_ENABLED` | `false` | Turns Slack on: connections, reply delivery and the token check. |
| `SLACK_SIGNAL` | `!` | The default signal; `slack signal` overrides it per workspace. |
| `STREAMPACK_SECURITY_ENFORCE_EXTERNAL_SECRETS` | `true` | The token check at start (shared with the other protocols). |

## Slack app

The app must have Socket Mode on, with an app-level token (`xapp-…`, scope `connections:write`) and
a bot token (`xoxb-…`). The bot calls `auth.test`, `chat.postMessage`, `users.info`,
`conversations.list`, `conversations.info`, `conversations.join` and `conversations.leave`, and
listens for `message` and `reaction_added` events. In Slack's terms that's roughly the
`chat:write`, `users:read`, `channels:read`, `groups:read` (to find private channels it's been
invited to), `channels:join`, `channels:manage` (to leave) and `reactions:read` scopes, and `message.channels`, `message.groups`,
`message.im` and `message.mpim` event subscriptions (with their `*:history` scopes) for the
conversations it should hear.

## Getting started

```
slack connect jvm-news xoxb-... xapp-...
slack autoconnect jvm-news true
slack join jvm-news #java
slack autojoin jvm-news #java true
slack status jvm-news
```

`slack join` puts the bot in `#java` at once; autojoin brings it back after a restart. Then
restart once with `SLACK_JVM_NEWS_BOT_TOKEN` and `SLACK_JVM_NEWS_APP_TOKEN` set, as the start asks.

## Inside

| Component | Role |
|-----------|------|
| `SlackAdminOperation` | The `slack …` commands. |
| `SlackService` | Workspaces and channels: registering, settings, status. |
| `SlackConnectionManager` | One live `SlackAdapter` per connected workspace; connects the autoconnect workspaces at start and joins their autojoin channels. |
| `SlackAdapter` | The Bolt Socket Mode app: addressing, users, actions, reactions, channel lookup and join, sending. |
| `SlackEgressSubscriber` | Delivers replies to the right workspace, unless muted or looping. |
| `SlackIdentityProvider` | Checks a Slack identity (workspace, user id) when linking it to an account. |
| `SlackSecretRefStartupGuard` | Moves tokens to environment variables and holds the start until they're set. |

Messages are handled on virtual threads, and the connection manager and commands run outside any
database transaction, so whatever is read from an entity must be loaded with it (as a channel's
workspace now is).
