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
mattermost signal <name> [character]
mattermost status [server]
```

Operational notes:

- `connect` with a URL and token registers the server; the token is externalized to `MATTERMOST_<NAME>_TOKEN` on the next restart.
- `channels` lists channels visible to the account across its teams; `join` accepts a channel id or a name and reports an ambiguous name rather than guessing.
- The account must already be a member of a private channel for the bot to read or post there.

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
sentiment <target>
```

Notes:

- `suggest` fetches the source URL, runs the AI pipeline when configured, and creates a draft idea.
- `ideas ...` manages draft ideas tagged `_idea`.
- `sentiment` analyzes recent conversation logs and may respond privately if the target differs from the requesting channel.

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
irc allow-ops <network> <#channel> <true|false>
irc signal <name> [character]
irc status [network]
```

Use these to manage runtime IRC networks, channels, logging, visibility, and signaling behavior.

## Slack Administration

`SUPER_ADMIN`:

```text
slack connect <name> [<bot-token> <app-token>]
slack disconnect <name>
slack remove <name>
slack autoconnect <name> <true|false>
slack join <workspace> <#channel>
slack leave <workspace> <#channel>
slack autojoin <workspace> <#channel> <true|false>
slack mute <workspace> <#channel>
slack unmute <workspace> <#channel>
slack automute <workspace> <#channel> <true|false>
slack visible <workspace> <#channel> <true|false>
slack logged <workspace> <#channel> <true|false>
slack signal <name> [character]
slack status [workspace]
```

## Command-Discovery Notes

Today, this page is the authoritative consolidated admin reference.

Issue `#18` tracks parser-driven help and grammar introspection so that future command discovery can
be emitted directly from the command grammar rather than maintained only as prose.
