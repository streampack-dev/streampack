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
- `moderated` decides whether a logged channel is watched for abuse (see [Moderation](#moderation)).
  On by default.

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

## Hidden lines

An admin can hide a line (see [Moderation](#moderation)). A hidden line is kept, flagged `hidden`,
and left out of every read exactly as a direct one is: the log browser and its search, `ask`,
`sentiment`, `article` and `be`, for everyone. `MessageLog`'s restriction and the `AND NOT hidden`
in each native query hold that rule beside the direct one. The one way back to a hidden line is the
moderation queries at the end of `MessageLogRepository`, which the admin moderation endpoints use
to show it, marked, so it can be judged and unhidden. Those queries still never read a direct line.

## Moderation

Abuse in a channel is behaviour, not vocabulary: someone hostile to *people*, again and again, not
someone swearing at a broken build. One message can't show that; a pattern over time can. So
moderation (`operation-moderation`, #150) has three parts, and a person makes every decision.

**Signals, on every message.** As each message in a public channel reaches the ingress channel,
it's scored, with no model involved, per person per channel:

| Signal | Adds | When |
|--------|------|------|
| Profanity | 0.5 | Swearing aimed at no one: at code, at oneself |
| Insult | 1 | A word for a person ("idiot") aimed at no one in particular |
| Aimed hostility | 8 | Swearing or an insult within three words of "you", or in a line naming someone else (`@name`, or the nick of anyone seen in the channel in the last hour) |
| Slur | 10 | Any one, from a short list; spelling games (`f4ggot`) are folded first |
| Threat | 10 | "I'll / I'm going to" a violent verb aimed at "you", "him", "her", "them" or someone named; "kill yourself", "kys" |
| Personal details | 3, or 8 with someone named | An email address, phone number or street address posted |
| Blocked link | 6 | A link to a host in `streampack.moderation.blocked-hosts` |
| Repetition | 1 per earlier copy | The same line again within the hour (case, spacing and punctuation aside) |
| Flood | 1 | Each line past six in ten seconds |

A score halves every half hour with nothing new. A line that takes it to 10 marks the person for
the next review; one aimed insult doesn't, two in a short while do, a slur or threat does alone. The
weights, threshold, timings and word lists are `streampack.moderation.*` settings. Scores live in
memory, are never shown to anyone, and a restart forgets them: they point the review somewhere,
and nothing else.

**Review, hourly.** Once an hour a `TickListener` takes the people marked since the last review.
For each, it reads that channel's last hour or so through `MessageLogService` (so never a direct or
hidden line), takes up to 20 of their lines with three lines either side, and asks the
moderation model (`AI_MODERATION_MODEL`, through `AiService.moderation()`) whether that person is
being hostile or abusive toward others, or just frustrated or joking: a short verdict, a reason, and
the line numbers that show it. The lines that raised signals are taken first, the strongest first,
and the rest are their most recent, so a person who keeps talking after the trouble can't push it
out of what the model reads. The transcript is the only thing sent, with the person's lines
marked. Usually nobody is marked, so there's no call at all. With AI off, or when the model doesn't
answer, the report is recorded from the signals alone, without a verdict. The model's thinking is
never asked for.

**Reports, and an admin decides.** Each review records a report: who (as the log names them, with
the protocol and service, and their account when known), where, the signals, the verdict, and the
ids of the lines read, the ones that raised signals and the ones the model cited. Nothing is hidden,
removed or banned automatically. From a report an admin can:

- **hide** lines: kept, flagged, and gone from public view (see [Hidden lines](#hidden-lines)), and
  **unhide** them again;
- **purge** lines that must not be kept (anything illegal): deleted for good, after confirming;
- **dismiss** the report as not abuse.

Each action is recorded with who did it and when, and a closed report stays readable. Bans stay with
each channel's own operators; the bot doesn't kick or ban. The admin endpoints are under
`/admin/moderation` (see [Blog HTTP API](../reference/blog-http-api.md#admin-moderation)), for
PUDL's Moderation window.

Direct conversations are never scored and never reviewed. A channel that isn't logged has nothing to
review, and a logged one can opt out with `moderated=false`.

