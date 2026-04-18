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
  Set an attribute such as `tags`.
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
```

These commands look up external reference material and often seed factoids so later requests are
faster.

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

Most feed and GitHub mutation commands are administrative, but some read commands are broadly
useful:

```text
feed list
feed subscriptions
github list
github subscriptions
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
