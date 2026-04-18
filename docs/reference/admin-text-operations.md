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

## GitHub Operations

`ADMIN`:

```text
github add owner/repo
github add owner/repo <token>
github subscribe owner/repo
github subscribe owner/repo to <destination-uri>
github unsubscribe owner/repo
github unsubscribe owner/repo from <destination-uri>
github remove owner/repo
github webhook owner/repo
github webhook private owner/repo
```

Readable without admin:

```text
github list
github subscriptions
github subscriptions for <destination-uri>
```

Operational notes:

- `github add owner/repo <token>` is the authenticated registration path.
- `github webhook owner/repo` validates and seeds the repository before switching to webhook mode.
- `github webhook private owner/repo` skips remote validation and is intended for operator-managed
  private repositories.

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
