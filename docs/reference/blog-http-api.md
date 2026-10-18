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
| `GET /posts/search?q=spring&page=0&size=20` | Searches published posts; see [Search](#search). |
| `GET /posts/popular?page=0&size=3` | Lists published posts ordered by decayed access temperature. |

A post's `tags` never include system tags (those starting with `_`, such as `_idea`), and
`GET /posts?tag=` with a system tag lists nothing; with an alias (`k8s`) it lists the tag it means
(`kubernetes`). Tags sent when a post is created or edited are normalized (`#C#` is `c#`,
`load-testing` is `load testing`) and go through the vocabulary: an alias is stored as its tag, a
stoplisted term is dropped, and a new tag is created, queued for an admin when it looks doubtful.
The response carries the tags as stored. See [Tag Names](tags.md#the-vocabulary).

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
      "categories": ["articles"],
      "versionKinds": ["audio"]
    }
  ],
  "page": 0,
  "totalPages": 1,
  "totalCount": 1
}
```

Each post's `versionKinds` lists the kinds of version it has, sorted, such as `audio` for a
recording, so a list can mark it; it's empty for most posts, and while media is off (#200). The
versions themselves are in the post's details. The admin drafts list (`GET /admin/posts/pending`)
carries it too.

### Search

`GET /posts/search` searches the title, tags, excerpt and body text of visible posts, ranked by
relevance. Words are stemmed (`emergent` finds `emerging`); quoted words match exactly
(`"emergent"`). The full grammar, and the limits on a query's length, its quotes and the page
size, are in [Post Search Syntax](post-search-syntax.md).

Each result carries `match`, a passage that shows why it matched:

```json
"match": {
  "source": "body",
  "text": "Some preamble. An emerging runtime could use the same modules",
  "marks": [{ "start": 18, "end": 26 }]
}
```

- `source` is the field the passage comes from: `body`, `excerpt`, `title` or `tags`. It's the
  first of these, in that order, with a match.
- `text` is plain text, with no markup. ` … ` joins separate extracts, and begins or ends one cut
  from a longer text.
- `marks` are the matching words, in order, as ranges of `text` counted in UTF-16 code units, as
  JavaScript's `String.prototype.slice` counts them. A stemmed match marks the word as written in
  the post (`emerging` for a search of `emergent`). A quoted match marks the exact words.

`match` is null in other listings.

Popularity is based on `lib-temperature`: each successful direct post read and explicit post access
event accrues a `blog.post` / `hit` signal. Scores decay over time, so older traffic gradually matters
less than recent traffic.

## Public Post Details

| Endpoint | Purpose |
|----------|---------|
| `GET /posts/{year}/{month}/{slug}` | Reads a published post by canonical or alias slug. |
| `GET /posts/{id}` | Reads a published post by UUID. |
| `GET /pages/{slug}` | Reads an approved system post: one in any `_` category, such as `_pages`. See [System Posts](#system-posts). |

Successful `GET /posts/{year}/{month}/{slug}` and `GET /posts/{id}` requests are pure reads. They do
not record post access or change temperature buckets. UI clients should call `POST /posts/{id}/access`
when a post link is opened from client-side navigation.

A post's details carry its `versions`, such as a recording of it; see
[Post Versions and Media](#post-versions-and-media).


## System Posts

A post in a category whose name starts with `_` (`_pages`, `_sidebar`, `_test`, or any other) is system content (#254). It's reachable at its own address and nowhere else:

- **Reachable:** `GET /pages/{slug}`, `GET /posts/{id}`, and on the front ends `/posts/{slug}`. A post made in a `_` category gets an undated slug, such as `about`. A post that joins one later keeps the slug it had.
- **Left out of:** post lists and the board, Popular Reads, tag pages, search, every feed (`/blog/rss.xml`, `/blog/atom.xml`, `/feed.xml`, `/feed.atom`, `/podcast.xml`), and outgoing links: no Webmentions, Pingbacks or autosubscribing.
- **Still in:** `/sitemap.xml`, which lists every published post, pages included.

A post that leaves its last `_` category is an ordinary post from then on, and its links are sent then. To try a post on the live site, see [Try a Post on the Live Site](../how-to/blog/try-a-post-on-the-live-site.md).

## Post Versions and Media

A post can have **versions**: the post in another form, belonging to it and recording which edit of
it it was made from (#196). The post's markdown stays the original. A recording of the post (`kind`
`audio`) is the only kind so far, and a post has at most one version per kind (and variant, which is
null for audio). Media is off until `STREAMPACK_MEDIA_DIR` is set; see
[Configure Media Storage](../how-to/deploy/configure-media-storage.md).

### In the post API

Post details (`GET /posts/{year}/{month}/{slug}`, `GET /posts/{id}`, `GET /pages/{slug}`, and the
answers to editing and approving a post) carry `versions`, empty when the post has none or media is
off:

```json
"versions": [
  {
    "kind": "audio",
    "variant": null,
    "mediaType": "audio/mpeg",
    "size": 4123456,
    "duration": 257,
    "url": "https://api.bytecode.news/media/posts/019d.../audio",
    "note": "read by the author",
    "maker": "Joseph Ottinger",
    "current": true,
    "createdAt": "2026-10-11T12:00:00Z",
    "replacedAt": null
  }
]
```

`duration` is whole seconds, null when unknown. `url` is absolute, on the API's own origin
(`BASE_URL`), and stays the same when the version is replaced; front ends must not rewrite it.
`current` is false once the post's markdown has been edited since the version was attached (the
version records a hash of the markdown it was made from); nothing else acts on it. Show the kinds you
know and ignore the rest. Post lists (`ContentListResponse`) carry only the kinds, as each post's
`versionKinds`.

### Serving

| Endpoint | Purpose |
|----------|---------|
| `GET /media/posts/{postId}/{kind}` | The version's bytes, such as the post's recording. |
| `GET /media/posts/{postId}/{kind}/{variant}` | The same, for a variant. |

Public, read-only, for published, undeleted posts; an admin may also fetch an unpublished post's (to
preview it), with `Cache-Control: private, no-store`. Anything else is `404`, as is everything while
media is off. Responses carry `Content-Type`, `Content-Length`, `Accept-Ranges: bytes`, an `ETag`
from the content's SHA-256, `Last-Modified` and `Cache-Control: public, max-age=300`
(`STREAMPACK_MEDIA_CACHE_MAX_AGE`). `Range` requests answer `206` with the bytes asked for (`416`
past the end), so players can seek; `If-None-Match` answers `304`. A replaced file keeps its address
and gets a new ETag.

### Uploading (admin)

| Endpoint | Purpose |
|----------|---------|
| `POST /admin/posts/{id}/versions/{kind}` | Attaches the post's version of `kind`, or replaces it. `201` when attached, `200` when replaced. |
| `DELETE /admin/posts/{id}/versions/{kind}` | Removes it and its file. `204`. |

ADMIN only (`401`, `403`). The upload is `multipart/form-data`:

| Part | Required | Meaning |
|------|----------|---------|
| `file` | Yes | The recording: MP3 (`audio/mpeg`), MP4/M4A (`audio/mp4`), Ogg Vorbis (`audio/ogg`) or Ogg Opus (`audio/ogg` or `audio/opus`). |
| `duration` | No | Length in whole seconds. Left out, it's read from the file's headers (MP3 Xing/VBRI or constant bitrate, MP4 `mvhd`, Ogg granule position), or null if they don't say. |
| `note` | No | Up to 500 characters, such as "read by the author". |

The file's type is decided by its first bytes. A declared type that isn't accepted, or disagrees with
the bytes, is refused; a part sent as `application/octet-stream` (or with no type) takes the type of
its bytes. The answer is the version as the post API shows it.

```bash
curl -X POST "$API/admin/posts/$POST_ID/versions/audio" \
  -H "Authorization: Bearer $TOKEN" \
  -F "file=@reading.mp3;type=audio/mpeg" -F "note=read by the author"
```

| Status | When |
|--------|------|
| `400` | A kind other than `audio`, an empty file, a duration outside 1 second to 7 days, or a note too long or with control characters. |
| `404` | No such post, or it's deleted; for `DELETE`, the post has no such version. |
| `409` | Another upload of the same version was being stored at once; try again. |
| `413` | The file is larger than `STREAMPACK_MEDIA_MAX_SIZE` (100 MB by default). |
| `415` | Not audio of an accepted type, or not the type it was sent as. |
| `503` | Media isn't configured (`STREAMPACK_MEDIA_DIR`). |

Deleting a post (a purge of an erased user's content) deletes its versions and their files. A
soft-deleted post keeps them, unserved, in case it's restored.

### Feeds

The post feeds, `/feed.xml`, `/feed.atom`, `/blog/rss.xml` and `/blog/atom.xml`, share one channel
title and description: `BLOG_FEED_TITLE` (default `BLOG_SITE_NAME`) and `BLOG_FEED_DESCRIPTION`.

`/feed.xml` and `/blog/rss.xml` give a post with a recording an
`<enclosure url="…" length="…" type="…"/>`; `/feed.atom` and `/blog/atom.xml` a
`<link rel="enclosure" href="…" length="…" type="…"/>`. `/podcast.xml` is `/feed.xml` limited to the
posts with a recording, with the `itunes:` tags (the show's author, artwork, category, explicit and
owner; each episode's duration, title and summary). Its channel is configured with `PODCAST_*`; see
[The Podcast Feed](../how-to/deploy/configure-media-storage.md#the-podcast-feed). Enclosure
addresses are the media addresses above, on the API's origin.

## Channel Logs

The public log browser reads these. Each takes the caller's credentials if there are any (the
`access_token` cookie, else a bearer token) and answers anonymous callers too; what a caller sees
depends on who they are.

A log day runs from midnight to midnight in the instance's log zone, `STREAMPACK_LOG_ZONE`
(UTC by default), so every reader sees the same day. `GET /features` gives it as `logs.zone`
(`{"logs": {"zone": "America/New_York"}}`) for labelling days. Lines' `timestamp`s stay UTC
instants.

| Endpoint | Purpose |
|----------|---------|
| `GET /logs/provenances` | Lists the channels the caller may browse, most recently active first. |
| `GET /logs/channels/{protocol}/{service}/{name}` | Finds a channel by its readable address: `irc/libera/primate`. |
| `GET /logs?provenance=<uri>&day=YYYY-MM-DD&events=all` | One log day of a channel's log, oldest first. `day` defaults to today. |
| `GET /logs/search?provenance=<uri>&q=<text>&sender=<nick>&page=0&size=50&events=all` | Searches one channel's log, newest first. |

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
`sender`, `content`, `direction` (`INBOUND` for what was said, `OUTBOUND` for the bot's
replies) and `kind`. A day returns at most 5000 entries, the earliest. A `day` that isn't
`YYYY-MM-DD` is a `400`.

**Kinds and events.** Each line's `kind` is `MESSAGE` for something said (`/me` actions too), or a
channel event: `JOIN`, `PART`, `QUIT`, `NICK` or `TOPIC` (see
[The Message Log](../explanation/message-log.md#each-line-has-a-kind)). The day view and search take:

- `events=all` (the default): every line, as before;
- `events=none`: what was said only, without the events;
- `kinds=JOIN,PART`: only these kinds, comma-separated, any case. With `events` as well, a line has
  to pass both.

An `events` other than `all` or `none`, or an unknown kind, is a `400`. Direct and hidden lines stay
out under every filter.

`GET /logs/search` needs `q`, `sender`, or both: `q` matches text anywhere in a line, ignoring case,
as written (`%` and `_` are literal), and is 3 to 200 characters; `sender` is a nick as logged,
ignoring case, up to 255 characters, and alone lists everything that person said in the channel.
`size` is 1 to 100. It answers `LogSearchResponse`: `provenanceUri`, `query`, `sender`, `page`,
`size`, `totalCount`, `totalPages`, and `hits`, each an entry as above (with its `kind`) plus its
log `day`, so a client can link to the day view. `events` and `kinds` narrow it as they do the day
view; `totalCount` counts only what they let through. Searches are limited to 30 a minute for each signed-in caller, and
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

## Admin Secrets

The environment variables the server needs set before its next start (#209): credentials given at
the console are stored in the database until their variables exist, and with
`STREAMPACK_SECURITY_ENFORCE_EXTERNAL_SECRETS` on, a start fails until they do. For active
`SUPER_ADMIN` accounts (`401` without a credential, `403` otherwise). The console's `secrets`
command gives the same list as text.

| Endpoint | Purpose |
|----------|---------|
| `GET /admin/secrets` | `{"required": [{"variable": "...", "description": "...", "state": "..."}]}`, sorted by variable. |

`state` is `STORED_IN_DATABASE` (given at the console, kept as typed until `variable` is set; the
next start then replaces it with a reference) or `NOT_SET` (stored as a reference to `variable`,
which the environment doesn't have). Values are never returned. An empty list means the next start
needs nothing more. The server reads its environment when it starts, so a variable added to `.env`
shows as set only after the container is recreated. See
[Move Credentials into the Environment](../how-to/deploy/move-credentials-into-the-environment.md).

A setup tool on the server's own box can call this, and the web console, with the token in
`STREAMPACK_LOCAL_ADMIN_TOKEN_FILE` as its bearer credential; see
[The Local Admin Token](../explanation/local-admin-token.md).

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
| `GET /admin/moderation/logs?provenance=<uri>&day=YYYY-MM-DD` | One log day of a channel's log with every line's id, hidden lines included and marked; `day` defaults to today. |
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
the model pointed at it; `day`: its log day, for a link to the log day; `signals`, `weight` and
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

## Admin Tags

The tag vocabulary's admin side (#140), for the pudl tag window: the review queue of doubtful new
tags, aliases and the stoplist. See [Tag Names](tags.md#the-vocabulary) for what each does. Every
endpoint is for `ADMIN` and `SUPER_ADMIN`: `401` signed out, `403` for anyone else. Tags and terms
go in bodies and query parameters, never the path, since a tag may hold `/` or `#`. Every change
is recorded with who made it and when.

| Endpoint | Purpose |
|----------|---------|
| `GET /admin/tags/review?status=open&page=0&size=50` | The queue: `status` is `open` (default), `aliased`, `split`, `kept`, `dismissed` or `all`; `size` is 1 to 100. The AI's most confident first, then newest. |
| `GET /admin/tags/review/{id}` | One entry, open or decided (`TagReviewEntry`, below). |
| `POST /admin/tags/review/{id}/alias` | Aliases the entry's tag to an existing one, `{"tag": "compilers"}` (`TagReviewAliasRequest`): its posts and factoids are re-pointed in one transaction. Marks it `ALIASED`. `?dryRun=true` previews it. |
| `POST /admin/tags/review/{id}/split` | Splits the entry's tag into parts, `{"parts": ["java", "kotlin"]}`, or the hint's parts with no body. Marks it `SPLIT`. `?dryRun=true` previews it. |
| `POST /admin/tags/review/{id}/keep` | Keeps the tag. Marks it `KEPT`. |
| `POST /admin/tags/review/{id}/dismiss` | Leaves the tag as it is. Marks it `DISMISSED`. |
| `GET /admin/tags/aliases` | Every alias, `[{alias, tag, createdBy, createdAt}]`, by alias. |
| `POST /admin/tags/aliases` | `{"alias": "k8s", "tag": "kubernetes"}` (`TagAliasRequest`, both required): makes an alias, re-pointing whatever carries it. `?dryRun=true` previews it. |
| `DELETE /admin/tags/aliases?alias=k8s` | Removes an alias; nothing is re-pointed back. |
| `GET /admin/tags/stoplist` | Every stoplisted term, `[{term, createdBy, createdAt}]`. |
| `POST /admin/tags/stoplist` | `{"term": "self-hosted"}`: dropped from tags written from now on. |
| `DELETE /admin/tags/stoplist?term=self%20hosted` | Takes a term off the stoplist. |
| `GET /admin/tags/actions?limit=50` | The latest changes, newest first, `[{action, subject, detail, actor, actedAt}]`. |

An unknown entry, alias or term is a `404`; a request the vocabulary refuses (a target that isn't
a tag, the same tag, a system tag, an entry already decided, a split into fewer than two parts) is
a `400`.

`GET /admin/tags/review` answers `TagReviewListResponse`:

```json
{
  "openCount": 2, "totalCount": 2, "totalPages": 1,
  "entries": [
    {
      "id": "0199...", "tag": "compiler", "firstSeen": "2026-10-08T14:00:00Z", "source": "post",
      "hintKind": "PLURAL", "hintTags": ["compilers"],
      "aiCandidate": "compilers", "aiConfidence": 0.97, "aiReason": "Singular of compilers.",
      "aiModel": "claude-haiku-4-5-20251001",
      "status": "OPEN", "actedBy": null, "actedAt": null
    }
  ]
}
```

`hintKind` is `PLURAL` (`hintTags[0]` is the tag it pairs with), `MISSING_COMMA` (`hintTags` are
its parts) or `AI` (only the AI found something; `hintTags[0]` is its candidate). The `ai` fields
are `null` when AI is off or hasn't answered. `source` is what first wrote the tag: `post`,
`factoid`, or `rss` (a feed tag promoted or created, below).

`openCount` is how many entries are open in all, whatever `status` asked for, for a launcher badge.
`totalCount` is how many entries the `status` asked for has in all, across every page, and
`totalPages` how many pages of `size` that makes (`0` when there are none), so any view can show
"page N of M". The spec marks the two optional, for older clients; the server always sends them.
`GET /admin/tags/review/{id}` answers one `TagReviewEntry`, as the list shows it.

The alias, split, keep and dismiss actions, and `POST /admin/tags/aliases`, answer
`TagChangeResult`: `{tag, now, posts, factoids}`, the tag acted on, what it now stands for, and how
many posts and factoids were re-pointed.

The review alias takes `TagReviewAliasRequest`, `{"tag"}` only: the entry names the tag being
aliased. `POST /admin/tags/aliases` takes `TagAliasRequest`, `{"alias", "tag"}`, both required.

### Previewing an alias or a split

`?dryRun=true` on `POST /admin/tags/review/{id}/alias`, `POST /admin/tags/review/{id}/split` and
`POST /admin/tags/aliases` previews the action, for a confirm step: it takes the same body and
answers the same `TagChangeResult`, with the posts and factoids the action would re-point, but
changes nothing (no tag, alias, entry or `tag_action` is touched). The action runs for real in a
transaction that is rolled back, so the counts are the ones it would produce at that moment, and a
request the action would refuse is refused the same way (`400`, `404`). Without `dryRun`, or with
`dryRun=false`, the action is taken.

## Admin Feed Tags

Feed entries' own tags (#139) that the vocabulary didn't know: the waiting ones, for the pudl tag
window, and an admin's decision on one. See [Tag Names](tags.md#feed-tags). Every endpoint is for
`ADMIN` and `SUPER_ADMIN` (`401` signed out, `403` for anyone else), and feed tags go in bodies,
never the path.

| Endpoint | Purpose |
|----------|---------|
| `GET /admin/rss/tags?status=waiting&page=0&size=50` | Feed tags: `status` is `waiting` (default), `promoted`, `created`, `mapped`, `ignored` or `all`; `size` is 1 to 100. The most carried first. |
| `POST /admin/rss/tags/map` | `{"name": "golang", "tag": "go"}`: the feed tag becomes an alias of the existing tag (off the stoplist first, if it was on it). Marks it `MAPPED`. |
| `POST /admin/rss/tags/ignore` | `{"name": "rumour"}`: stoplists it (its alias removed first, if it had one). Marks it `IGNORED`. |
| `POST /admin/rss/tags/create` | `{"name": "helidon"}`: creates it as a tag now, through the create rule (review-queue hints, AI near-miss). Marks it `CREATED`. |

A feed tag no feed has carried is a `404`; a map to something that isn't a tag is a `400`. Each
action answers the feed tag as decided (`FeedTagEntry`). The list answers `FeedTagListResponse`:

```json
{
  "waitingCount": 12, "promoteEntries": 3, "promoteFeeds": 2, "totalCount": 12, "totalPages": 1,
  "tags": [
    {
      "name": "quarkus", "written": ["Quarkus", "quarkus"], "status": "WAITING",
      "entries": 2, "feeds": 1,
      "firstSeen": "2026-10-08T14:00:00Z", "lastSeen": "2026-10-08T15:00:00Z",
      "tag": null, "decidedBy": null, "decidedAt": null,
      "examples": [
        {"entryId": "0199...", "title": "Quarkus 4", "link": "https://...", "feedTitle": "A blog"}
      ]
    }
  ]
}
```

`waitingCount` is how many feed tags wait in all, whatever `status` asked for, for a launcher
badge. `totalCount` is how many the `status` asked for has in all, across every page, and
`totalPages` how many pages of `size` that makes (`0` when there are none); optional in the spec
for older clients, always sent. A waiting feed tag the vocabulary has decided some other way is
settled as the page is listed, and the totals are counted after that.

`tag` is the tag a decided feed tag became or maps to. `decidedBy` is the admin, `rss` for one
promoted past the threshold, or `vocabulary` for one that became a tag, an alias or a stoplisted
term some other way (`/admin/tags`, a post) while it waited.

Stored feed items (`GET /rss/items`) carry `categories`, the item's own tags as the feed wrote
them, and `tags`, the BCN tags those map to; both are empty lists when there are none.

## Admin Feed Item Ratings

Admins rate feed items to learn what's worth writing about (#187, the annotation phase of #16):
`RATES` (worth an article), `MIGHT` or `DULL`. A model's hidden guess, off by default, is measured
against the ratings before anything is shown or suggested. Every endpoint is for `ADMIN` and
`SUPER_ADMIN` (`401` signed out, `403` for anyone else). **Ratings and guesses are never public**:
`GET /rss/items` is unchanged.

| Endpoint | Purpose |
|----------|---------|
| `PUT /admin/rss/items/{id}/rating` | `{"rating": "RATES"}` (`RATES`, `MIGHT` or `DULL`): rates the item, replacing any rating it had. Answers `RssItemRatingResponse`. The same rating again changes nothing. |
| `DELETE /admin/rss/items/{id}/rating` | Clears the item's rating (`204`, whether it had one or not). |
| `GET /admin/rss/items?rating=all&page=0&size=50` | Items as `/rss/items` lists them, with their ratings. `rating` is `all` (default), `rates`, `might`, `dull` or `unrated`; `size` is 1 to 100; `feed` and `title` filter as on `/rss/items`. |
| `GET /admin/rss/ratings.csv` | Every item with a rating or a guess, as CSV, for offline evaluation. |
| `GET /admin/rss/rating-stats` | How the guess agrees with the ratings. |
| `GET /admin/rss/rating-guesses?label=all&rated=all&page=0&size=50` | The model's guesses, newest first, with the editor's rating and whether they agree. `label` is `all` (default), `rates`, `might` or `dull`; `rated` is `all` (default), `yes` or `no`; `size` is 1 to 100; `feed` and `title` filter as on `/rss/items`. |
| `POST /admin/rss/rating-guesses/run` | Runs a guess pass now, as the daily one does. `409` while the guess is off, or while a pass is running. |

An unknown item is a `404`; a rating that isn't one of the three is a `400`, as is a bad filter,
a negative page or a size outside 1 to 100 on either list. Every set and clear is
kept in `rss_item_rating_history` (what it became, what it was, who, when).

`PUT` answers `RssItemRatingResponse`, every field always sent:

```json
{"itemId": "0199...", "rating": "RATES", "ratedBy": "joe", "ratedAt": "2026-10-09T14:00:00Z"}
```

The list answers `AdminRssItemsResponse`, `{items, page, totalPages, totalCount}`, where each item
(`AdminRssItemResponse`) has every field a `/rss/items` item has, plus `rating`, `ratedBy` and
`ratedAt` (`null` when unrated). `totalCount` and `totalPages` are for the `rating` asked for. It
**never** carries the model's guess, so the guess can't bias the ratings.

The CSV (`text/csv`, RFC 4180, CRLF line ends) has a header row and these columns: `item_id`,
`feed`, `title`, `link`, `published`, `rating`, `rated_by`, `rated_at`, `guess`,
`guess_confidence`, `guess_reason`, `guess_model`, `guess_source` (`content`, `page`, `summary` or
`title`: what the model judged), `guessed_at`, `guess_rubric` (the rubric's hash; empty for guesses
made before it was recorded). Rated items come first, newest rating first, then
the guessed-only ones; a column is empty where there's no rating or no guess. A text field that a
spreadsheet would take for a formula (starting `=`, `+`, `-`, `@`) is prefixed with `'`.

The stats (`RssRatingStatsResponse`) compare the items that have both a rating and a guess:

```json
{
  "counts": {"rates": 2, "might": 1, "dull": 2, "rated": 5, "guessed": 5, "compared": 4},
  "agreement": 0.5,
  "perClass": [
    {"rating": "RATES", "rated": 2, "guessed": 2, "agreed": 1, "recall": 0.5, "precision": 0.5},
    {"rating": "MIGHT", "rated": 1, "guessed": 1, "agreed": 1, "recall": 1.0, "precision": 1.0},
    {"rating": "DULL", "rated": 1, "guessed": 1, "agreed": 0, "recall": 0.0, "precision": 0.0}
  ],
  "confusion": [{"rated": "RATES", "guessed": "RATES", "count": 1}, "... all nine cells ..."],
  "guessedRatesRatedDull": [{"itemId": "0199...", "title": "...", "feedTitle": "..."}],
  "ratedRatesGuessedDull": [{"itemId": "0199...", "title": "...", "feedTitle": "..."}]
}
```

`counts.rates`, `might` and `dull` count all ratings; `compared` the items with both. `recall` is
the share of the items rated so that were guessed so, `precision` the share guessed so that were
rated so; each, and `agreement`, is `null` with nothing to divide by. `confusion` always has all nine
cells.

The list of guesses answers `RssRatingGuessesResponse`, `{items, page, totalPages, totalCount}`,
with `totalCount` and `totalPages` for the filters asked for. Each item (`RssRatingGuessResponse`):

```json
{
  "itemId": "0199...", "title": "...", "link": "https://...", "feedTitle": "...",
  "label": "RATES", "confidence": 0.8, "reason": "A new take on an old idea.",
  "textSource": "CONTENT", "model": "claude-haiku-...", "rubric": "3f9c0a1b2d4e",
  "guessedAt": "2026-10-11T10:00:00Z", "rating": "MIGHT", "agrees": false
}
```

`textSource` is `CONTENT`, `PAGE`, `SUMMARY` or `TITLE`; `rubric` is `null` for guesses made before
the rubric was recorded; `rating` and `agrees` are `null` when the item is unrated. Unrated items
are listed too: seeing a guess may bias a rating, which the editor accepts, so it's kept apart from
the item list, and `GET /admin/rss/items` still never carries a guess.

The manual run answers `RssRatingGuessRunResponse`: `{candidates, calls, stored, skipped}`: the
items it found with no guess, the calls to the model (one per chunk), the guesses stored, and the
items left without one (tried again in a later pass).

### The model's hidden guess

Off unless `RSS_RATING_MODEL_GUESS=true` (and AI is on). Once a day a pass sends the items received
in the last two days with no guess to `AI_MODERATION_MODEL`, 20 to a call, one plain call per
chunk, with structured output: `{itemId, label, confidence, reason}` per item. Each item is judged
on its text, cut to 4,000 characters: the feed's own full content when it gave any, else the
article page's (fetched once, through the guarded fetcher, and kept), else its summary. The rubric,
the instructions for the answer, and the examples (the editor's ten latest `RATES` and five latest
`DULL`, or seed exemplars until there are three `RATES`) are the system prompt, the same for every
call in a pass.

The rubric is the `rss-rating-rubric` prompt, through `lib-generative`: the bundled default
(`service-rss/src/main/resources/dev/streampack/rss/prompts/rss-rating-rubric.txt`) is replaced by
`${STREAMPACK_GENERATIVE_PROMPT_DIR}/rss-rating-rubric.clj` or `.txt` without a rebuild. A broken or
blank override falls back to the default. An override changes how items are judged, not how the
answer is read: the text sources and the answer's shape stay in code, after the rubric. It's
rendered once per pass, and each guess stores the first 12 hex characters of the SHA-256 of the
rubric it was made with (`rubric`; `null` before #192). An item is still guessed once: changing the
rubric doesn't guess it again. A bad or partial answer
is skipped and logged; it never blocks polling. See [service-rss](../../service-rss/README.md#item-ratings)
and the `RSS_RATING_*` [environment variables](environment-variables.md).

## Atlas

A map of the site's tags (ui-pudl#184), so every front end draws the same geography. Places are
tags; regions are tags used together. The layout is computed once and stored; see
[The Atlas](../explanation/atlas.md) for how it's laid out and why it holds still.

| Endpoint | Purpose |
|----------|---------|
| `GET /atlas` | The map: regions, places with positions, counts and pins, and the world's bounds. Public and conditional (`ETag`, `Last-Modified`). |
| `GET /atlas/places/{tag}` | One place (matched ignoring case; an alias finds its tag's place) with every article and factoid found there. `404` if the tag isn't on the map. |
| `POST /admin/atlas/relayout` | Lays the whole map out again, stores it and returns it as `GET /atlas` would. `ADMIN` and `SUPER_ADMIN` only: `401` signed out, `403` for anyone else. |

`GET /atlas` answers `AtlasResponse`:

```json
{
  "regions": [
    { "id": 1, "name": "java · spring", "x": 0.0, "y": 0.0, "r": 141.3, "uncharted": false },
    { "id": 0, "name": "Uncharted", "x": 520.0, "y": 12.0, "r": 48.0, "uncharted": true }
  ],
  "places": [
    {
      "tag": "java", "region": 1, "x": 0.0, "y": 0.0,
      "articleCount": 23, "factoidCount": 5, "kind": "shared",
      "r": 17.8, "labelSize": 15.3, "minor": false,
      "pins": [
        { "kind": "article", "title": "Virtual threads", "slug": "2026/10/virtual-threads",
          "x": 0.0, "y": -32.5, "publishedAt": "2026-10-01T12:00:00Z" },
        { "kind": "factoid", "title": "jvm", "selector": "jvm", "x": 20.1, "y": -26.3,
          "createdAt": "2026-06-02T09:00:00Z", "updatedAt": "2026-09-30T18:00:00Z" }
      ]
    }
  ],
  "bounds": { "x": -221.3, "y": -239.3, "width": 829.3, "height": 460.6 },
  "laidOutAt": "2026-10-08T12:00:00Z"
}
```

- **Positions** are world units; `bounds` is the world with a margin and room above each region
  for its name. Regions are biggest first with Uncharted (`id` 0, tags only ever used alone) last;
  places are in region order, biggest first.
- **Counts** are the taxonomy's: `articleCount` is `tags` in `GET /taxonomy` (published posts only:
  approved, due, not deleted, not in a hidden category) and `factoidCount` is `factoidTags`. Every
  tag with a count is on the map, and only those.
- **`kind`** is `articles`, `factoids` or `shared` (both). ui-pudl draws a disc, a ring and a
  diamond, so the three are told apart without colour.
- **`r`** is the mark's radius (`4 + 2.6·√(articles + factoids)`), `labelSize` the name's size in
  pixels (`10 + √size`, at most 20) and `minor` a place whose name shows only close up (size
  under 3).
- **`pins`** are the place's newest articles and its factoids by name, at most 8 of each, each
  already placed around the mark: an article has `slug` and `publishedAt`, a factoid `selector`,
  `createdAt` and `updatedAt`. A post with several tags is pinned at each of its places.
- **What's new** is the front end's: posts by `publishedAt`, factoids by `createdAt` (new) and
  `updatedAt` (changed), from `GET /posts` and `GET /factoids`. Their `tags` name the places they're
  found at. `GET /factoids?since=<ISO-8601 instant>` lists only the factoids set or changed at or
  after it, newest first (setting any attribute moves a factoid's `updatedAt`), so a front end
  needn't read every page; `since` doesn't combine with `q` (400).

`GET /atlas/places/{tag}` answers `AtlasPlaceDetailResponse`: `place` (as above), `regionName`,
`articles` (every published post carrying the tag, newest first, each `{title, slug, publishedAt,
tags}`) and `factoids` (every factoid carrying it, by selector, each `{selector, createdAt,
updatedAt, tags}`). It's how a tag's factoids are listed: `GET /factoids` searches, but doesn't
list by tag.

## Generated OpenAPI

The generated OpenAPI document is `docs/openapi.json`. Do not edit it by hand; regenerate it with
the command documented in [OpenAPI](openapi.md).
