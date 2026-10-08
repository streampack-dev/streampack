# Admin Text Operations

This page is repository-facing operator documentation for the bundled Streampack text-command
surface.

It is intentionally separate from the user-facing bot page. These commands mutate runtime state,
manage protocol integrations, or otherwise require elevated privileges.

## Role Model

- `ADMIN`
  Can perform most operational mutations.
- `SUPER_ADMIN`
  Can manage users, identity bindings, service enablement, and protocol adapters such as IRC or Slack.

## Core Administration

### User and Identity Management

`SUPER_ADMIN`:

```text
create user <username> <email> <displayName> [role]
link help [protocol]
link user <username> <protocol> <serviceId> <externalIdentifier>
unlink user <username> <protocol> <serviceId> <externalIdentifier>
```

`ADMIN` or `SUPER_ADMIN`:

```text
alter user <username> role <role>
alter user <username> email <email>
alter user <username> displayname <name>
alter user <username> username <new-username>
```

Important constraints:

- `ADMIN` cannot modify admin-level users into higher roles.
- `SUPER_ADMIN` cannot change their own role.

### Service and Operation Configuration

Public read commands:

```text
service list
operation config
channel config
channel config for <pattern>
```

Mutation commands:

```text
service enable <name>
service disable <name>
operation enable <group>
operation disable <group>
operation set <group> <key> <value>
channel enable <group> [for <pattern>]
channel disable <group> [for <pattern>]
channel set <group> <key> <value> [for <pattern>]
```

Notes:

- `service ...` mutations require `SUPER_ADMIN` and take effect after restart.
- `operation ...` and `channel ...` mutations require `ADMIN`.
- When `channel ... for` is omitted, the current provenance is used.

## Factoid Moderation

Normal users can create and query factoids. Admins get moderation controls:

```text
selector.lock
selector.unlock
forget selector
forget selector.attribute
```

`lock` and `unlock` require `ADMIN`. Locked factoids reject ordinary updates and forget operations
until unlocked.

## Feed Operations

`ADMIN`:

```text
feed add <url>
feed subscribe <feed-url>
feed subscribe <feed-url> to <destination-uri>
feed unsubscribe <feed-url>
feed unsubscribe <feed-url> to <destination-uri>
feed remove <feed-url>
```

Readable without admin:

```text
feed list
feed subscriptions
feed subscriptions for <destination-uri>
```

Operational notes:

- `feed add` accepts direct feed URLs or site URLs and performs autodiscovery.
- OPML import and export are HTTP admin endpoints, not text commands:
  - `GET /admin/rss/opml`
  - `POST /admin/rss/opml/import`

## URL Title Ignore List

`ADMIN`:

```text
url ignore add <host | *.host | host/path>
url ignore delete <host | *.host | host/path>
```

Readable without admin:

```text
url ignore list
```

Operational notes:

- `repopack.com/project` covers that path and below on whole segments (not `/projects`); `*.repopack.com` covers the bare host and every subdomain.
- Entries are normalized on add and delete (lower-cased, no scheme, `www.` or trailing slash).
- Sign-in and bot-check titles ("Repopack · Sign in", "Just a moment...") are never emitted, independent of this list; the phrases are configured by `streampack.urltitle.suppressed-titles`. See `operation-urltitle/README.md`.

## Channel Controls

Every registered channel carries five flags, set with the per-protocol `visible`, `logged`, `moderated`, `automute`, and `autojoin` commands (see [The Message Log](../explanation/message-log.md) for the reasoning):

- `logged=false` stops message capture for that channel entirely: neither inbound messages nor the bot's replies are written to the message log. It is not merely a browsing switch.
- `visible=false` hides a channel's log from anonymous and non-admin browsing; admins still see it.
- `moderated=false` takes a logged channel out of abuse detection: its messages aren't scored and it's never reviewed. On by default; a channel with `logged=false` isn't moderated either, as there's nothing to review. Moderation only reports to admins (the Moderation window over `/admin/moderation`); it never hides, purges or bans anything itself. See [The Message Log](../explanation/message-log.md#moderation).
- `automute` (also set by `mute`/`unmute`) holds the bot's replies back; it still reads, runs commands and logs.
- `autojoin` rejoins the channel whenever its network, workspace or server connects. It's off for a newly registered channel.
- The flags are created when a channel is registered with `join`. IRC channels, and public Mattermost channels, register visible and logged; private Mattermost channels and direct or group messages joined by id register hidden and unlogged. Opt a private channel in explicitly if its history should be kept.
- A conversation that was never registered has no flags, and is logged.
- Direct conversations (IRC private messages, Discord, Slack and Mattermost DMs, and Slack and Mattermost group DMs) are logged marked direct, and nothing reads them back: not the log browser or its search, not `ask`, `sentiment`, `article`'s logs or `be`, for anyone, admins included. `logged=false` on a registered one keeps it out of the log entirely.
- Connect commands that carry credentials (`irc connect`, `slack connect`, `mattermost connect`) are redacted before they reach the message log, however the command was spaced or cased; a migration redacts copies logged before this rule existed.
- Anything shaped like a credential (vendor tokens such as `ghp_…`, `xoxb-…`, `sk-ant-…` or `AKIA…`, private key blocks, JWTs, URLs with a password, `password=`/`token:`/`<PREFIX>_KEY=` assignments with random-looking values, and `hunter2`, which becomes `*******`) is replaced with `[REDACTED:<kind>]` in what's logged, inbound and outbound, direct conversations included. The sender of a scrubbed channel message is told privately, at most once every few minutes; see [The Message Log](../explanation/message-log.md#secrets-are-scrubbed). Migration V62 scrubbed the log written before this. Extra shapes can be added under `streampack.secret-scrubbing.extra-patterns`.

## Mattermost Operations

Present only when `streampack.mattermost.enabled` is true. All require `SUPER_ADMIN`:

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
mattermost moderated <server> <channel-id-or-name> <true|false>
mattermost signal <name> [character]
mattermost status [server]
```

Operational notes:

- `connect` with a URL and token registers the server; the token is externalized to `MATTERMOST_<NAME>_TOKEN` once that variable exists, and startup refuses to run with enforcement on until it does. Token values are never printed.
- `autojoin` channels are joined through the API on every connect and reconnect (public channels; private ones need an admin to add the account).
- `channels` lists channels visible to the account across its teams; `join` accepts a channel id or a name and reports an ambiguous name rather than guessing. Names repeat across teams, so a name registered on two teams must be addressed by id afterwards.
- `join` on a public channel adds the account to it on Mattermost; `leave` removes the account, so the server stops sending that channel's posts, and retires the channel's record (its flags are kept for a later `join`). Private channels must be joined by an admin on Mattermost; `join` registers them hidden and unlogged. Every command but `join` needs a registered channel.
- `signal` takes effect on the live connection immediately.
- A dropped socket is retried with doubling delays (from `MATTERMOST_RECONNECT_DELAY`, capped at five minutes) until it reconnects or the server is disconnected; `status` with no name shows `reconnecting` meanwhile; `status <name>` says `not connected`. An unreachable server at startup does not stop the application.

## Forge Instances and `on <host>`

Forge modules (GitHub and GitLab) share one grammar for choosing which installation a project lives on. Each forge has a hosted default instance (`github.com`, `gitlab.com`) that is registered automatically. Other installations are registered once with `<forge> instance add <url> [token]`, and then any command that names a project may end in `on <host>`:

```text
github add owner/repo on ghe.example.com
github subscribe owner/repo on ghe.example.com to <destination-uri>
```

Rules:

- `on <host>` follows the project path (and the token, if one is given); a `to <destination-uri>` or `from <destination-uri>` clause may follow it.
- Omitting `on <host>` means the hosted default.
- Hosts are matched case-insensitively.
- A host that has not been registered is an error, not an implicit registration.
- The instance token is the default credential for its projects; a project token given at `add` time overrides it.

## Pipeline Filters

Both forges accept pipeline filters between the project path and `on <host>` on `subscribe`. Every subscription receives issues, change requests, and releases; filters add pipeline outcomes on top and are reported once per settlement.

| Filter | Reports |
|--------|---------|
| `pipelines` | Pipelines that belong to a pull or merge request |
| `pipelines:default-branch` | Pipelines on the project's default branch |
| `pipelines:branch:<name>` | Pipelines on one named branch |

Any filter takes a trailing `:failed` to report only pipelines that need attention, e.g. `pipelines:branch:development:failed`. Several filters may be given. Re-subscribing with filters replaces them; unsubscribe and subscribe to drop them. `subscriptions` shows filters in brackets after each project.

## GitHub Operations

`ADMIN`:

```text
github instance add <url>
github instance add <url> <token>
github add owner/repo [on <host>]
github add owner/repo <token> [on <host>]
github subscribe owner/repo [<filter>…] [on <host>]
github subscribe owner/repo [<filter>…] [on <host>] to <destination-uri>
github unsubscribe owner/repo [on <host>]
github unsubscribe owner/repo [on <host>] from <destination-uri>
github remove owner/repo [on <host>]
github webhook owner/repo [on <host>]
github webhook private owner/repo [on <host>]
```

Readable without admin:

```text
github instance list
github list
github subscriptions
github subscriptions for <destination-uri>
```

Operational notes:

- `github instance add https://ghe.example.com` registers a GitHub Enterprise Server; a base URL gets `/api/v3` appended, a full API URL is kept as given.
- `github add owner/repo <token>` is the authenticated registration path.
- `github webhook owner/repo` validates and seeds the repository before switching to webhook mode.
- `github webhook private owner/repo` skips remote validation and is intended for operator-managed
  private repositories.

## GitLab Operations

Present only when `streampack.gitlab.enabled` is true.

`ADMIN`:

```text
gitlab instance add <url>
gitlab instance add <url> <token>
gitlab add group/project [on <host>]
gitlab add group/project <token> [on <host>]
gitlab subscribe group/project [<filter>…] [on <host>]
gitlab subscribe group/project [<filter>…] [on <host>] to <destination-uri>
gitlab unsubscribe group/project [on <host>]
gitlab unsubscribe group/project [on <host>] from <destination-uri>
gitlab remove group/project [on <host>]
gitlab webhook group/project [on <host>]
gitlab webhook private group/project [on <host>]
```

Readable without admin:

```text
gitlab instance list
gitlab list
gitlab subscriptions
gitlab subscriptions for <destination-uri>
```

Operational notes:

- Project paths include subgroups: `group/subgroup/project`.
- `gitlab instance add https://gitlab.example.com` registers a self-hosted GitLab; a base URL gets `/api/v4` appended, a full API URL is kept as given.
- `gitlab webhook group/project` validates and seeds the project before switching to webhook mode; the secret token is sent to the requesting user and must travel over HTTPS because GitLab sends it verbatim.
- `gitlab webhook private group/project` skips the API lookup and records the project by path only.

## Idea and AI Operations

`ADMIN`:

```text
suggest <http(s)://url>
ideas
ideas search <term>
ideas remove #<n>
sentiment [target]
```

Notes:

- `suggest` fetches the source URL, runs the AI pipeline when configured, and creates a draft idea.
- `ideas ...` manages draft ideas tagged `_idea`.
- `sentiment` analyzes recent conversation logs: the channel it's asked in, or `[target]` (`#channel` on the same network, or a provenance URI). It answers in the channel with just the score line, or privately, naming the channel (`Sentiment for #other: …`), if the target differs from the requesting channel. In a direct conversation it asks for a channel, as direct lines are never read.

## Game Moderation

`ADMIN`:

```text
hangman block <word>
hangman unblock <word>
```

These commands maintain the blocked-word list used by the hangman game.

## Bridge Operations

Readable without admin:

```text
bridge provenance
bridge info
```

`ADMIN`:

```text
bridge copy <source-uri> <target-uri>
bridge remove <source-uri> <target-uri>
bridge list
```

Bridges are directional. Create the reverse direction explicitly if you want a bidirectional mirror.

## IRC Administration

`SUPER_ADMIN`:

```text
irc connect <name> [<host> <nick> [saslAccount] [saslPassword]]
irc disconnect <name>
irc remove <name>
irc autoconnect <name> <true|false>
irc join <network> <#channel>
irc leave <network> <#channel>
irc autojoin <network> <#channel> <true|false>
irc mute <network> <#channel>
irc unmute <network> <#channel>
irc automute <network> <#channel> <true|false>
irc visible <network> <#channel> <true|false>
irc logged <network> <#channel> <true|false>
irc moderated <network> <#channel> <true|false>
irc allow-ops <network> <#channel> <true|false>
irc signal <name> [character]
irc status [network]
```

Present only when `IRC_ENABLED` is true. Notes:

- `connect` with details registers the network (or updates it, or restores a removed one) and connects; `connect <name>` alone connects with the stored details. SASL credentials are moved to `IRC_<NAME>_SASL_ACCOUNT` and `IRC_<NAME>_SASL_PASSWORD` at the next start, and startup refuses to run with enforcement on until they're set.
- `remove` soft-deletes the network and its channels; `disconnect` keeps them registered.
- `autoconnect` and `autojoin` are off for a new network or channel: a channel joined once isn't rejoined after a restart until `autojoin` is on.
- Channels are named with their `#`. `irc join` registers a channel visible and logged.
- `mute`/`unmute` and `automute` set the same stored flag: replies are held back, and the channel is still read, answered internally and logged.
- `allow-ops false` (the default) makes the bot take operator status off itself when it's given it.
- `signal` sets one network's signal character; with no character, the network goes back to the default `!`.
- `status` shows each connected network's joined channels.

See [service-irc](../../service-irc/README.md) for addressing, reply wrapping and the send queue.

## Slack Administration

`SUPER_ADMIN`:

```text
slack connect <name> [<bot-token> <app-token>]
slack disconnect <name>
slack remove <name>
slack autoconnect <name> <true|false>
slack join <workspace> <#channel|id>
slack leave <workspace> <#channel|id>
slack autojoin <workspace> <#channel|id> <true|false>
slack mute <workspace> <#channel|id>
slack unmute <workspace> <#channel|id>
slack automute <workspace> <#channel|id> <true|false>
slack visible <workspace> <#channel|id> <true|false>
slack logged <workspace> <#channel|id> <true|false>
slack moderated <workspace> <#channel|id> <true|false>
slack signal <name> [character]
slack status [workspace]
```

Present only when `SLACK_ENABLED` is true. Notes:

- `connect` with tokens (the `xoxb-` bot token and the `xapp-` app token Socket Mode needs) registers the workspace, or replaces its tokens and reconnects. The tokens are externalized to `SLACK_<NAME>_BOT_TOKEN` and `SLACK_<NAME>_APP_TOKEN`, and startup refuses to run with enforcement on until they're set.
- A channel is named `#name`, `name` or by its Slack id. Its flags are kept by its id (`slack://<workspace>/<channel-id>`), the address its messages arrive with.
- `join` while connected finds the channel, registers it, and puts the bot in a public one; a private one needs the bot invited in Slack, and registers hidden and unlogged. While disconnected, `join` takes only an id.
- `leave` takes the bot out of the channel; the channel stays registered.
- `status` with Slack on reports only whether workspaces are connected.

See [service-slack](../../service-slack/README.md) for addressing, reactions and the Slack app's setup.

## Command-Discovery Notes

Today, this page is the authoritative consolidated admin reference.

Issue `#18` tracks parser-driven help and grammar introspection so that future command discovery can
be emitted directly from the command grammar rather than maintained only as prose.
