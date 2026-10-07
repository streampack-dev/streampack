# operation-moderation

`operation-moderation` is abuse detection for public channels (#150): cheap signals on every
message, an hourly review of the few people they point at, and reports for an admin to act on.
Nothing here hides, removes or bans anything by itself. The admin HTTP endpoints are in
`service-moderation`.

## Parts

| Part | What it does |
|------|--------------|
| `ModerationIngressInterceptor` | On the ingress channel beside the logging interceptor: scores every message said in a public IRC, Discord, Slack or Mattermost channel. Skips direct conversations, unlogged channels and channels with `moderated=false`. Never changes or stops a message. |
| `SignalScorer` | Scores one line with no model: profanity and insults (low on their own, high when aimed at "you" or someone named), slurs, threats, personal details, blocked links. |
| `ModerationScores` | Each person's score in each channel, in memory: adds repetition and flood signals, halves every half hour, and marks a person for review when a line takes them to the threshold. Never shown to anyone. |
| `ModerationReviewTickListener` / `ModerationReviewService` | Once an hour, for each marked person: reads their recent lines in that channel with context through `MessageLogService` (never direct or hidden lines), asks the moderation model (`AiService.moderation()`) for a short verdict, and records a `moderation_report`. With AI off, the report is recorded from the signals alone. |
| `ModerationService` | What an admin does: list and read reports (hidden lines included, marked), hide, unhide and purge lines, dismiss a report. Each action is recorded in `moderation_action` with who and when. |
| `ModerationWords` | The default word lists, short on purpose, each replaceable in configuration. |

## Configuration

`streampack.moderation.*` (see `ModerationProperties`): `enabled` (`true`), `threshold` (`10`),
`half-life` (`30m`), `window` (`1h`), `review-interval` (`1h`), `review-lines` (`20`),
`context-lines` (`3`), `flood-lines` (`6`) in `flood-window` (`10s`), `weights.*`, `blocked-hosts`,
and the word lists `profanity`, `insults` and `slurs` (an entry ending in `*` matches words it
starts).

Per channel, the protocols' `moderated <...> <true|false>` admin commands opt a logged channel out.

## Tables

`moderation_report` and `moderation_action` (`V67`). `lib-core`'s `V66` adds `message_log.hidden`
and `channel_control_options.moderated`.

See [The Message Log](../docs/explanation/message-log.md#moderation) for the reasoning.
