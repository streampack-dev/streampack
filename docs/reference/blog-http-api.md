# Blog HTTP API

The bundled `server-streampack` distribution exposes the blog/site API through `service-blog`.
These endpoints are intended for browser and UI clients.

## Sign In with a One-Time Code

Sign-in is a two-step exchange: ask for a code, then present it. Codes are delivered over a
*channel*, and the channels a deployment offers are listed under `authentication.codeChannels`
in `GET /features`:

```json
"codeChannels": [
  { "channel": "email", "servers": [] },
  { "channel": "mattermost", "servers": ["work"] }
]
```

Email needs no server. Chat channels list the servers Streampack is currently connected to, and a
request must name one of them.

| Endpoint | Body | Purpose |
|----------|------|---------|
| `POST /auth/otp/request` | `{ "channel": "email", "address": "me@example.com" }` | Sends a code to an email address. |
| `POST /auth/otp/request` | `{ "channel": "mattermost", "server": "work", "address": "alice" }` | Sends a code as a Mattermost direct message. |
| `POST /auth/otp/verify` | The same identity plus `"code": "123456"` | Exchanges the code for a session. |

The original `{ "email": "me@example.com" }` body is still accepted on both endpoints and means
the email channel. `channel` values are lowercase and case-insensitive.

`POST /auth/otp/request` answers `202` with the same message whether or not the identity exists,
so callers cannot probe for registered users or usernames. A code is delivered only when the
channel can resolve the identity: any plausible email address for email, or a username that
exists on the named connected server for Mattermost. Unknown servers and usernames get the same
`202` with nothing sent.

`POST /auth/otp/verify` answers `200` with a `LoginResponse` (`token`, `refreshToken`,
`principal`) and sets the session cookies, `401` when the code is wrong, expired, or was
issued for a different channel, or `429` when the identity has been tried too often (see below). Codes are scoped to the channel and recipient they were issued
for, so an email code cannot verify a Mattermost identity and vice versa.

Accounts are created on first sign-in. An email sign-in converges on the account owning that
address. A chat sign-in looks for an account bound to that server and user; when there is none it
registers one with the chat username (made unique if taken), the chat display name, a binding to
that server and user, and **no email address**. Such accounts can later add an email through the
profile endpoints, at which point email sign-in also works for them.

Codes expire after a short window and each recipient may hold only a few active codes at a time;
further requests are accepted but not delivered until an older code expires or is used.

The limits, with their defaults:

| Limit | Default | Setting |
|-------|---------|---------|
| Code lifetime | 10 minutes | `streampack.otp.expiration-minutes` |
| Active codes per recipient | 3 | `streampack.otp.max-active-codes` |
| Wrong codes before lockout | 5 | `streampack.otp.max-failed-attempts` (`OTP_MAX_FAILED_ATTEMPTS`) |
| Verify attempts per identity | 10 at once, then 1 a minute | fixed |

Wrong codes are counted per channel and recipient. The wrong code that reaches the limit expires
every active code the recipient holds, and the count starts over; the user asks for a new code,
which works as usual. A count also starts over once no wrong code has been presented for a code's
lifetime. A wrong code, an expired code and a locked-out recipient all answer the same `401` with
the same message, so a caller can't tell them apart.

Verify attempts are also throttled per identity as presented (channel, server and address, case
ignored), whether or not the identity exists. Past the limit, `POST /auth/otp/verify` answers
`429` until the bucket refills.

## Public Post Lists

| Endpoint | Purpose |
|----------|---------|
| `GET /posts?page=0&size=20` | Lists published blog posts in publication order. |
| `GET /posts?category=kotlin&page=0&size=20` | Lists published posts in a category. |
| `GET /posts?tag=kotlin&page=0&size=20` | Lists published posts with a tag. |
| `GET /posts/search?q=spring&page=0&size=20` | Searches published posts. |
| `GET /posts/popular?page=0&size=3` | Lists published posts ordered by decayed access temperature. |

`GET /posts/popular` defaults to `size=3` so homepage sections can request a compact
"popular posts" widget without specifying pagination. Larger callers may pass an explicit `size`.

The response shape is `ContentListResponse`:

```json
{
  "posts": [
    {
      "id": "019d...",
      "title": "Example Post",
      "slug": "2026/04/example-post",
      "excerpt": "Short summary",
      "authorDisplayName": "Author",
      "publishedAt": "2026-04-22T12:00:00Z",
      "sortOrder": 0,
      "commentCount": 0,
      "tags": ["java"],
      "categories": ["articles"]
    }
  ],
  "page": 0,
  "totalPages": 1,
  "totalCount": 1
}
```

Popularity is based on `lib-temperature`: each successful direct post read and explicit post access
event accrues a `blog.post` / `hit` signal. Scores decay over time, so older traffic gradually matters
less than recent traffic.

## Public Post Details

| Endpoint | Purpose |
|----------|---------|
| `GET /posts/{year}/{month}/{slug}` | Reads a published post by canonical or alias slug. |
| `GET /posts/{id}` | Reads a published post by UUID. |
| `GET /pages/{slug}` | Reads an approved system page from the `_pages` category. |

Successful `GET /posts/{year}/{month}/{slug}` and `GET /posts/{id}` requests are pure reads. They do
not record post access or change temperature buckets. UI clients should call `POST /posts/{id}/access`
when a post link is opened from client-side navigation.

## Channel Logs

The public log browser reads these. Each takes the caller's credentials if there are any (the
`access_token` cookie, else a bearer token) and answers anonymous callers too; what a caller sees
depends on who they are.

| Endpoint | Purpose |
|----------|---------|
| `GET /logs/provenances` | Lists the channels the caller may browse, most recently active first. |
| `GET /logs/channels/{protocol}/{service}/{name}` | Finds a channel by its readable address: `irc/libera/primate`. |
| `GET /logs?provenance=<uri>&day=YYYY-MM-DD` | One UTC day of a channel's log, oldest first. `day` defaults to today. |
| `GET /logs/search?provenance=<uri>&q=<text>&sender=<nick>&page=0&size=50` | Searches one channel's log, newest first. |

**Which channels.** A channel is browsable when it's registered (with a protocol's `join`), active,
and `logged`; anonymous callers and ordinary users also need it `visible`, while `ADMIN` and
`SUPER_ADMIN` see hidden ones too. Only channels are listed: IRC targets starting with `#`, and
Discord, Slack and Mattermost channels. A `provenance` that isn't browsable for the caller, whether
it doesn't exist or is hidden from them, is a `404` on the day view and the search alike, so
neither can reveal that a hidden channel exists.

Direct conversations (private messages, DMs, group DMs) are never returned, to anyone, and neither
are lines an admin has hidden (see [Admin Moderation](#admin-moderation)); see
[The Message Log](../explanation/message-log.md).

**Readable addresses.** Each channel in `/logs/provenances` has a `path`, such as `irc/libera/primate`,
for front ends to put in their own addresses (`/logs/irc/libera/primate`), and
`GET /logs/channels/{protocol}/{service}/{name}` turns one back into the channel: it answers
`{ "provenanceUri": "irc://libera/%23primate", "path": "irc/libera/primate" }`, and the
`provenanceUri` is what the day view and search take.
- An IRC name is matched as written, then with `#`, then with `##` (Libera has `##` channels), so
  `primate` is `#primate` when there is one. The path for `##primate` beside a `#primate` names its
  hashes: `irc/libera/%23%23primate`.
- Slack, Mattermost and Discord channels go by their names (`mattermost/work/town-square`), or by
  their ids where a name isn't known or two channels share it (every Mattermost team has a
  `town-square`).
- Only channels the caller may browse are matched. Any other name is a `404`, as a hidden channel
  is, so an address can't reveal one.

**`provenance`** is the channel's provenance URI exactly as `/logs/provenances` gives it
(`irc://libera/%23java`), URL-encoded as a query parameter (`irc%3A%2F%2Flibera%2F%2523java`).

`GET /logs/provenances` answers `LogProvenanceListResponse`:

```json
{
  "provenances": [
    {
      "provenanceUri": "irc://libera/%23java",
      "protocol": "irc",
      "serviceId": "libera",
      "replyTo": "#java",
      "latestTimestamp": "2026-10-06T14:02:11Z",
      "latestSender": "alice",
      "latestContentPreview": "the latest line, flattened to one line and cut at 140 characters",
      "path": "irc/libera/java"
    }
  ]
}
```

The `latest*` fields are `null` for a channel with nothing logged yet.

`GET /logs` answers `LogDayResponse`: `provenanceUri`, `day`, and `entries`, each with `timestamp`,
`sender`, `content` and `direction` (`INBOUND` for what was said, `OUTBOUND` for the bot's
replies). A day returns at most 5000 entries, the earliest. A `day` that isn't `YYYY-MM-DD` is a
`400`.

`GET /logs/search` needs `q`, `sender`, or both: `q` matches text anywhere in a line, ignoring case,
as written (`%` and `_` are literal), and is 3 to 200 characters; `sender` is a nick as logged,
ignoring case, up to 255 characters, and alone lists everything that person said in the channel.
`size` is 1 to 100. It answers `LogSearchResponse`: `provenanceUri`, `query`, `sender`, `page`,
`size`, `totalCount`, `totalPages`, and `hits`, each an entry as above plus its UTC `day`, so a
client can link to the day view. Searches are limited to 30 a minute for each signed-in caller, and
60 a minute shared by all anonymous callers (`429` past that). A bad parameter is a `400`.

## Admin Web Console

Streampack's text commands for signed-in administrators, typed in a browser (#115). Commands go in
as at the stdin console (`aho-corasick`, `foo is bar`, `calc 2+2`: addressed, no prefix); output
comes back on a server-sent event stream shared by all of that admin's windows. Both endpoints are
for active `ADMIN` and `SUPER_ADMIN` accounts, checked against the user store on every request,
and each command runs with the account's authority as it stands when the command runs.

| Endpoint | Purpose |
|----------|---------|
| `POST /admin/console` | Submits `{"line": "..."}`; answers `202` with `{"correlationId": "..."}`. |
| `GET /admin/console/stream` | The admin's console output, as `text/event-stream`. |

**Submitting.** The body is JSON (`415` otherwise), at most `streampack.webconsole.max-body`
bytes (`413`), holding one non-blank line with no CR, LF or NUL and at most
`streampack.webconsole.max-line` characters (`400`). The request must carry `X-Web-Console: 1`.
A request authenticated by the `access_token` cookie must also come from an origin in
`CORS_ORIGINS` (its `Origin`, or failing that its `Referer`); a request naming any untrusted origin
is refused (`403`). A bearer-authenticated request without an origin (a server calling the API)
needs none. A valid cookie wins over a bearer header. Admins may submit
`streampack.webconsole.commands-per-minute` commands a minute, however many tokens or windows they
use (`429`).

`202` means the command was accepted, not that it succeeded: its output arrives on the stream,
possibly before the `202` does. Nothing is retried, and a lost answer doesn't mean the command
didn't run.

**The stream.** Answered with `Cache-Control: no-store` and `X-Accel-Buffering: no`. Events:

| Event | Data |
|-------|------|
| `ready` | `{"username": "..."}`, once the stream is registered. Submit only after it. |
| `result` | `{"correlationId": "...", "status": "success" \| "error" \| "unhandled", "text": "..."}` |
| comment `:heartbeat` | Every `streampack.webconsole.heartbeat`. |

A command may produce no results, one, or several, and none marks it finished. `correlationId` is
the command a result answers, or `null` for output nobody asked for here (a notification sent to
the console's address, or a `tell` from elsewhere). `text` is plain text, possibly several lines,
and absent when `unhandled` ("no matching response"); clients show it as text, never markup. Output
larger than `streampack.webconsole.max-event-bytes` arrives as an `error` saying so, never
truncated.

An admin may have `streampack.webconsole.max-streams-per-user` streams open and open
`stream-opens-per-minute` a minute (`429`). A stream closes when its credential expires, the account
is no longer an active admin (checked at each heartbeat), the client falls more than
`queue-events` events or `queue-bytes` bytes behind, a write fails, or after `max-stream-age`.
Reconnecting starts a fresh stream: nothing missed is replayed, so a client should show that there
may be a gap, and never resubmit commands to fill it.

**Addresses.** An admin's console is `webconsole://web/users/<user id>`: anything sent there (by
`EgressNotifier`, say) reaches their open streams while they're an admin. It can't be bridged, and
it's kept out of the public log browser. Its commands are logged, redacted, as other ingress is;
its output is logged as its outcome only (`[web console: success]`), never its text, so
log-derived context (Ask's) sees the commands but not their answers. From the console, `tell`
needs a full address (`tell irc://libera/#java hello`).

**Deployment.** The registry is in memory: one backend instance only. Behind a proxy, don't buffer
`/admin/console/stream` and allow a read timeout longer than the heartbeat; see
[Configure a Reverse Proxy](../how-to/deploy/configure-reverse-proxy.md).

## Admin Moderation

Abuse reports from the hourly review, and what admins do with them (#150), for PUDL's Moderation
window. Every endpoint is for `ADMIN` and `SUPER_ADMIN` (the `access_token` cookie, else a bearer
token): `401` signed out, `403` for anyone else. Nothing is hidden, purged or dismissed except by a
request here, and each is recorded with who made it and when. See
[The Message Log](../explanation/message-log.md#moderation) for how reports come about.

| Endpoint | Purpose |
|----------|---------|
| `GET /admin/moderation/reports?status=&page=0&size=25` | Reports, open first, newest first within each; `status` (`open`, `dismissed`, `actioned`) narrows to one. `size` is 1 to 100. |
| `GET /admin/moderation/reports/{id}` | One report with its excerpt and its actions. |
| `POST /admin/moderation/reports/{id}/hide` | Hides lines of the report's excerpt: `{"lineIds": [...], "note": "..."}`. Marks the report `ACTIONED`. |
| `POST /admin/moderation/reports/{id}/unhide` | Puts hidden lines of the excerpt back. The report's status stays. |
| `POST /admin/moderation/reports/{id}/purge` | Deletes lines of the excerpt for good: `{"lineIds": [...], "confirm": true, "note": "..."}`. Without `"confirm": true` it's a `400`. Marks the report `ACTIONED`. |
| `POST /admin/moderation/reports/{id}/dismiss` | Closes an open report as not abuse; the body (`{"note": "..."}`) is optional. A report that isn't open is a `400`. |
| `GET /admin/moderation/logs?provenance=<uri>&day=YYYY-MM-DD` | One UTC day of a channel's log with every line's id, hidden lines included and marked; `day` defaults to today. |
| `POST /admin/moderation/lines/hide` | Hides lines found in a log day rather than a report: `{"lineIds": [...], "note": "..."}`. |
| `POST /admin/moderation/lines/unhide` | Puts them back. |

`lineIds` are message log line ids, 1 to 500 of them. Lines acted on from a report must be in its
excerpt (`400` otherwise), and an unknown report is a `404`. `note` is optional everywhere. Direct
conversations are never read or changed by any of these.

`GET /admin/moderation/reports` answers `ReportListResponse`:

```json
{
  "page": 0, "size": 25, "totalCount": 3, "totalPages": 1, "openCount": 2,
  "reports": [
    {
      "id": "0199...", "provenanceUri": "irc://libera/%23java", "protocol": "irc",
      "serviceId": "libera", "channel": "#java", "sender": "troll", "userId": null,
      "score": 15.8, "signals": { "AIMED_HOSTILITY": 2 },
      "verdict": { "abusive": true, "reason": "Insults bob repeatedly.", "model": "claude-haiku-4-5-20251001" },
      "status": "OPEN", "windowStart": "2026-10-07T14:01:09Z", "windowEnd": "2026-10-07T14:06:40Z",
      "createdAt": "2026-10-07T15:00:00Z", "actedBy": null, "actedAt": null
    }
  ]
}
```

`signals` counts the lines that raised each signal: `PROFANITY`, `INSULT`, `AIMED_HOSTILITY`,
`SLUR`, `THREAT`, `PERSONAL_DETAILS`, `BLOCKED_LINK`, `REPETITION`, `FLOOD`. `verdict` is `null`
when AI was off or the model didn't answer. `channel` is the provenance's channel name or id.
`actedBy` and `actedAt` are the admin who last hid, purged or dismissed, and when.

`GET /admin/moderation/reports/{id}` answers `ReportDetail`: `report` (as above), `lines`, the lines
the review read, oldest first, each `{id, timestamp, day, sender, content, direction, hidden,
flagged, cited, signals, weight, strong}` (`flagged`: the person's line raised a signal; `cited`:
the model pointed at it; `day`: its UTC day, for a link to the log day; `signals`, `weight` and
`strong`: for a flagged line, the signals it raised, what it added, and whether that reaches
`strong-line-weight` so it's worth pre-checking for hiding (#169), all null for other lines and for
reports filed before they were kept), `purgedLineIds` (excerpt lines since purged), and
`actions`, oldest first, each `{id, reportId, action, lineIds, note, actor, actedAt}` with `action`
one of `HIDE`, `UNHIDE`, `PURGE`, `DISMISS`.

Each `POST` answers `ModerationActionResult`: `action` (as recorded) and `changed`, how many lines it
changed (lines already hidden, or already gone, aren't counted).

`GET /admin/moderation/logs` answers `ModerationLogDay`: `provenanceUri`, `day`, and `lines` as in
a report, with `flagged` and `cited` false. Hidden lines never appear in `GET /logs` or
`GET /logs/search`, for anyone, admins included; this is where an admin finds them.

## Generated OpenAPI

The generated OpenAPI document is `docs/openapi.json`. Do not edit it by hand; regenerate it with
the command documented in [OpenAPI](openapi.md).
