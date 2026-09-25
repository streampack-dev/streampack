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

## Generated OpenAPI

The generated OpenAPI document is `docs/openapi.json`. Do not edit it by hand; regenerate it with
the command documented in [OpenAPI](openapi.md).
