# The Message Log

Streampack keeps one protocol-agnostic log of what's said to it and what it says back. It is the
record behind the public log browser and its search, and the material for operations that look back
at a conversation: `ask` (recent context), `sentiment`, `article`'s `logs`, and `be`. This page
explains what goes into it, what keeps things out, and the one rule about what comes back out.

## What's written

Every inbound message is written as it reaches the ingress channel, and every reply as it leaves
through egress, each under its provenance URI (`irc://libera/%23java`,
`mattermost://work/<channel-id>`, `slack://jvm-news/<channel-id>`). Unaddressed chatter is logged
as well as commands, since the log is a record of the channel, not of the bot's work.

Three things change what's written:

- **Redaction.** A command that carries credentials (`irc connect`, `slack connect`,
  `mattermost connect`, `github add`, and the like) has its secret arguments replaced with
  `[REDACTED]` before it's written. Each operation declares its own rules, matched the way the
  command parser reads the command.
- **The web console.** A browser's console commands are logged, redacted, like any other ingress,
  but their output is logged as its outcome only (`[web console: success]`): an admin's console can
  show what nothing else should keep, and outbound text has no redaction.
- **`logged=false`.** A channel whose settings say it isn't logged has nothing written at all,
  inbound or outbound.

## Channel settings

A channel's settings are kept under the same provenance URI its messages arrive with. That is the
point of them: when a message comes in, the URI it carries finds the settings. A protocol whose
settings were kept under a different key (a Slack channel's name, while its messages carried its
id) has settings that silently apply to nothing.

- `logged` decides whether anything is written.
- `visible` decides whether a logged channel is listed in the public log browser. Admins see every
  logged channel either way.
- `automute` holds the bot's replies back without stopping it from listening; what it would have
  said is still logged.
- `autojoin` decides whether the bot rejoins the channel when its network, workspace or server
  connects.

Settings are created when a channel is registered with `join`. A conversation that was never
registered has none, and is logged by default; it isn't listed in the log browser, which lists only
registered channels.

## Direct conversations are kept, and never read

Private messages, DMs and group DMs are written to the log like everything else, marked `direct`.
Nothing reads them back out: not the log browser or its search, not `ask`, `sentiment`,
`article` or `be`, for anyone, admins included.

The two halves have different reasons. They're kept because the log is the record of what the bot
was told and what it did, and a record with holes in it can't answer for the bot's behaviour. They're
never read because a person talking to the bot directly hasn't spoken in a channel, and nothing the
bot does should turn their words into something others can see. `be`, which imitates someone from
their recent lines, would otherwise be a way to repeat a DM in a public channel.

The rule is held in two places so that a new feature can't step around it:

- `DirectConversations` (in `lib-core`) decides whether a provenance is direct from its address:
  an IRC target that isn't a channel, a Discord target with no guild, a Slack user id or DM channel
  type, a Mattermost direct or group channel. Protocol modules add detectors for what the address
  can't tell. The rules live in the core so they hold whether or not a protocol is enabled.
- `MessageLog` is restricted so that no entity query returns a direct row, and every native query
  in `MessageLogRepository` excludes them. A new way of reading the log has to do the same.

A registered direct conversation with `logged=false` is kept out of the log entirely, as any other
channel would be.
