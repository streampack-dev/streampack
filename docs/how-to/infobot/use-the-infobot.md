# Use the Infobot

This page is the user-facing command map for the bundled Streampack infobot.

Commands are shown without a protocol prefix. On a live protocol you will usually address the bot
with the configured signal character or mention style, for example `!calc 2+3`.

Some features are optional:

- AI-backed commands require AI support to be configured.
- Some operations may be disabled globally or per channel by operators.
- A few commands react to ambient text and do not require explicit addressing.

## Ambient Behavior

These features react to normal conversation instead of a dedicated command:

- `kotlin++`, `xml--`
  Adjust karma for a subject.
- paste a URL
  Streampack may emit the page title if URL-title lookup is enabled and the host is not ignored.

## Knowledge and Lookup

### Factoids

Use factoids for lightweight shared knowledge.

```text
spring
spring is A Java framework
spring.tags=java,framework
spring.tags
search framework
tag java
spring.stats
```

Common patterns:

- `selector`
  Read a factoid.
- `selector is value` or `selector=value`
  Create or update a factoid.
- `selector.attribute=value`
  Set an attribute such as `tags`. Tags are comma-separated and stored normalized: lowercased,
  a leading `#` dropped, `-` and `_` as spaces, repeats removed (`#Java, load-testing` is stored
  as `java,load testing`). See [Tag Names](../../reference/tags.md).
- `factoid set selector.attribute value`
  Explicit setter form for selectors or values that are awkward in shorthand.
- `factoid unset selector.attribute`
  Remove one attribute.
- `search term`
  Search factoid text.
- `tag term`
  Search by tag.
- `selector.stats`
  Show how often a factoid has been accessed.

### Dictionary and Specifications

```text
define ubiquitous
rfc 2616
jep456
pep 8
jsr 330
jcp 380
cve 2021-44228
cve org.apache.logging.log4j:log4j-core 2.14.1
cve npm:lodash
cve log4j
```

These commands look up external reference material and often seed factoids so later requests are
faster. `jcp` is another name for `jsr`.

`cve <term> [version]` takes a CVE id (`CVE-2021-44228` or `2021-44228`), Maven coordinates
(`group:artifact`), another ecosystem's package with a prefix (`npm:`, `go:`, `crates:`, `pypi:`,
`gem:`, `nuget:`, `composer:`, `hex:`, `pub:`) or a bare name. With a version it lists the CVEs that
affect it, worst first, and the lowest version OSV says fixes them all; without one it summarizes.
A bare name is a known alias (`log4j` is both `log4j:log4j` and log4j-core) or an artifactId Maven
Central has exactly one of; when several share it, they're listed for you to pick, and so are an
alias's packages when you give a version. See
[operation-specs](../../../operation-specs/README.md#cve-lookup).

> **`cve` isn't a security verdict.** Its answers come straight from CVE.org and OSV.dev, and are
> neither verified nor complete. "None known to OSV" doesn't mean safe. A fix version is OSV's
> data and may not exist for that package (OSV lists `2.0` as fixing `log4j:log4j`, which never
> had a 2.0). Check the advisory before acting.

### Calculator, Date, and Weather

```text
calc (42/3.14)*4
today
today list
tomorrow hebrew
weather Boston
```

Use these for quick utility lookups.

## Social and Messaging Tools

### Karma

```text
kotlin++
xml--
karma kotlin
top karma 5
bottom karma 5
```

Karma changes are ambient. Query and leaderboard commands are explicit.

### Tell

```text
tell alice build is green
tell #java release is live
tell irc://libera/%23java deploy is complete
```

Targets resolve relative to the current protocol unless you provide a full provenance URI.

Put a target in double quotes when it has a space in it. The message follows the closing quote:

```text
tell "foo bar" standup in five
tell "mattermost://work/foo bar" standup in five
```

In a full address, `%20` is also a space, and needs no quotes: `tell mattermost://work/foo%20bar hi`.

On Mattermost, a target can be a person or a channel name:

- `@name` sends a direct message from the bot to that person: `tell @dana.k the build is green`, or from somewhere else, `tell mattermost://work/@dana.k the build is green`.
- A channel's name sends to that channel. Use its URL name (`foo-bar` or `#foo-bar`), or its display name in quotes (`"Foo Bar"`). `mattermost channels <term>` shows the names.
- A channel id is used as it is.

A name is looked up while the bot is connected to that server. If the name isn't found, or it matches more than one channel, the bot tells you, and nothing is sent. Only public channels are found by name. Use a private channel's id.

### Who Am I?

```text
whoami
```

Send it to the bot in a direct message. It names the account you're using and its role. If your chat account isn't linked to one, it gives the `link user` line an admin types to link it, so send that to your admin. In a channel it only asks for a direct message.

### Bridge Discovery

```text
bridge provenance
bridge info
```

These two subcommands are readable by normal users and help you understand the current channel's
provenance and bridge state.

## Games

### Hangman

```text
hangman
hangman e
hangman solve compiler
hangman concede
```

### 21 Matches

```text
21
21 take 2
21 concede
```

### Safecracker

```text
safecracker
safecracker 3 0 3 0
safecracker concede
```

Each game keeps independent state per channel or DM context.

## AI and Creative Commands

These commands require AI support to be enabled.

### Ask

```text
ask why does virtual-thread pinning matter here?
```

The bot can use recent channel context when answering.

### Markov

```text
be alice
```

Generates short text in the style of a known sender when enough message history exists.

### Poetry

```text
poem distributed systems
a poem: Two roads diverged...
```

`poem` generates a poem. `a poem:` analyzes poem text.

## Idea Capture

### Interactive idea session

```text
article Better JVM startup stories
content We should compare CDS, CRaC, and native-image tradeoffs.
logs 30m
includeai
done
```

This is a multi-step flow for capturing an article idea from chat.

### Browse your drafts

Operators may expose idea browsing separately, but the core user-facing creation path is the
interactive `article` flow above.

## Feed and Repository Status

Most feed, GitHub, and GitLab mutation commands are administrative, but some read commands are broadly
useful:

```text
feed list
feed subscriptions
github instance list
github list
github subscriptions
gitlab instance list
gitlab list
gitlab subscriptions
```

Operators may grant broader access in their own deployments, but the bundled defaults treat most
registration and subscription changes as administrative work.

## Notes

- Factoid lookup, dictionary, specs, and calculator commands are usually the fastest way to get a
  concise answer.
- Karma and URL titles are ambient. They happen without a dedicated command.
- If a command appears not to work, the most common reasons are:
  - the operation is disabled in that channel
  - the feature requires AI or an external integration that is not configured
  - the command is admin-only
