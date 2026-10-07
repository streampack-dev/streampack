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
- **Scrubbing.** Anything shaped like a credential, wherever it appears in a message, is replaced
  with `[REDACTED:<kind>]`, both in what people say and in what the bot says back. See
  [Secrets are scrubbed](#secrets-are-scrubbed).
- **The web console.** A browser's console commands are logged, redacted, like any other ingress,
  but their output is logged as its outcome only (`[web console: success]`): an admin's console can
  show what nothing else should keep, and outbound text has no redaction rules, only the scrubbing.
- **`logged=false`.** A channel whose settings say it isn't logged has nothing written at all,
  inbound or outbound.

## Secrets are scrubbed

People paste secrets into chat: API keys, tokens, private keys. Redaction rules only cover the
commands that are meant to carry them, so everything that's logged, inbound and outbound, also
passes through `SecretScrubber` (in `lib-core`), which replaces anything shaped like a credential
with `[REDACTED:<kind>]` before it's written. The message itself still reaches the operations as it
was sent; only the record changes.

Detection is by shape, the way gitleaks and GitHub's secret scanning work, never by asking a model:
every message passes through it, and a few dozen precompiled patterns cost microseconds. It finds:

- tokens with a vendor's prefix: GitHub (`ghp_`, `gho_`, `ghu_`, `ghs_`, `ghr_`, `github_pat_`),
  GitLab (`glpat-`), Slack (`xoxb-`, `xoxp-`, `xapp-`, and webhook URLs), Anthropic (`sk-ant-`),
  OpenAI-style `sk-` keys, AWS (`AKIA`/`ASIA`), Google (`AIza`), Stripe (`sk_live_`, `rk_live_`),
  npm (`npm_`), Discord bot tokens and webhook URLs;
- private key blocks (`-----BEGIN … PRIVATE KEY-----`), JWTs, and the `user:password` part of a URL;
- `password=`, `token:`, `api_key=`, `secret=` and similar assignments, and `Bearer` tokens, only
  when the value looks random: long enough, more than one kind of character, and high enough in
  Shannon entropy. The word "password" in a sentence, `password=hunter2`, or `token: ${TOKEN}` are
  left alone.

A deployment can add shapes of its own without a release, under
`streampack.secret-scrubbing.extra-patterns` (each a `kind`, a `pattern`, and optionally the
`description` used when telling the sender).

### Telling the sender

When a message in a channel is scrubbed, the bot tells whoever sent it, privately: "I've removed what
looked like a GitHub token from the log of #java. If it was real, revoke it now: it was visible in
the channel." Scrubbing protects the record, not the moment; everyone in the channel saw it, which is
why the note says *revoke*. It never goes to the channel, which would only draw attention to what was
pasted, and it's sent at most once per person per five minutes
(`streampack.secret-scrubbing.notice-interval`), so a pasted file is one note, not one per line.

Each protocol module supplies a `SenderNotifier` that reaches the sender directly: an IRC private
message to their nick, a Discord DM, a Slack DM, a Mattermost direct channel. Adapters put the
sender's protocol identity on each message (the `streampack_sender_id` header) for it. A protocol with
no notifier, and events with no sender (joins, topic changes), are scrubbed but nobody is told.

Direct conversations are scrubbed, since they're kept, but the sender isn't told: nobody else saw
what they sent, so there's nothing to revoke in a hurry. The bot's own replies are scrubbed without a
note too; whoever sent the original was told when it came in.

The log written before scrubbing existed was cleaned once by migration V62, with the same built-in
patterns, direct rows included.

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
