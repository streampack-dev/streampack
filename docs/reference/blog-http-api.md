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
`principal`) and sets the session cookies, or `401` when the code is wrong, expired, or was
issued for a different channel. Codes are scoped to the channel and recipient they were issued
for, so an email code cannot verify a Mattermost identity and vice versa.

Accounts are created on first sign-in. An email sign-in converges on the account owning that
address. A chat sign-in looks for an account bound to that server and user; when there is none it
registers one with the chat username (made unique if taken), the chat display name, a binding to
that server and user, and **no email address**. Such accounts can later add an email through the
profile endpoints, at which point email sign-in also works for them.

Codes expire after a short window and each recipient may hold only a few active codes at a time;
further requests are accepted but not delivered until an older code expires or is used.

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

## Generated OpenAPI

The generated OpenAPI document is `docs/openapi.json`. Do not edit it by hand; regenerate it with
the command documented in [OpenAPI](openapi.md).
