# service-blog

`service-blog` is the HTTP-facing blog and account adapter for Streampack.

It owns:

- public and admin blog HTTP controllers
- OTP-based sign-in and token refresh
- account export, erasure, suspension, and unsuspension
- editor-side AI tag derivation
- the Atlas: the map of the site's tags every front end draws

Most operations in this module are typed request handlers used by HTTP controllers rather than
chat-facing bot commands.

## Public HTTP Endpoints

| Endpoint | Purpose |
|----------|---------|
| `GET /posts` | Lists published posts, optionally filtered by `category` or `tag`. |
| `GET /posts/search?q=...` | Searches published posts. |
| `GET /posts/popular` | Lists popular published posts, defaulting to `size=3`. |
| `GET /posts/{year}/{month}/{slug}` | Reads a post by slug without recording access. |
| `GET /posts/{id}` | Reads a post by UUID without recording access. |
| `POST /posts/{id}/access` | Records UI-driven post access without returning post content. |
| `GET /pages/{slug}` | Reads a system page from the `_pages` category. |
| `GET /atlas` | The Atlas: regions and places (tags) with positions, counts and pins. |
| `GET /atlas/places/{tag}` | One place on the Atlas, with every article and factoid found there. |

`GET /posts/popular` is intended for compact UI sections such as "popular posts" or "popular pages."
It returns the same `ContentListResponse` shape as the normal post listing, ordered by decayed
`blog.post` / `hit` temperature.

## The Atlas

`dev.streampack.blog.atlas` lays out a map of the site's tags (ui-pudl#184, moved here from ui-pudl so
ui-pudl and ui-primate draw the same one) and serves it at `GET /atlas`; see
[The Atlas](../docs/explanation/atlas.md) and the [Blog HTTP API](../docs/reference/blog-http-api.md#atlas).

- **Inputs** come from the database: published posts' tags (`PostTagRepository.findPublishedTagging`,
  the rules `GET /taxonomy` counts by), factoids' tags (`FindFactoidTaggingRequest`, answered by
  operation-factoid) and the counts from the taxonomy snapshot.
- **Storage** is `atlas_region` and `atlas_place` (migration V69). The first request lays the map out
  and stores it; a new tag is placed beside its strongest relative; a tag no longer used is left
  off and keeps its row. Only `POST /admin/atlas/relayout` (admins) lays it out again.
- **Cost:** the answer is kept in memory, keyed by a validator over the taxonomy's inputs, factoid
  and slug changes and the stored map, so a request with nothing changed costs a few aggregate
  queries; the same validator gives `GET /atlas` its `ETag`.



| Operation | Command / payload | Purpose |
|-----------|-------------------|---------|
| `OtpRequestOperation` | `OtpRequest` | Generates a one-time code and sends it by email. |
| `OtpVerifyOperation` | `OtpVerifyRequest` | Validates a one-time code and authenticates the user, creating an account if needed. |
| `TokenRefreshOperation` | `TokenRefreshRequest` | Issues a fresh JWT from a valid token or validated user ID. |
| `ExportUserDataOperation` | `ExportUserDataRequest` | Exports a user's profile, posts, and comments for GDPR-style review. |
| `DeleteAccountOperation` | `DeleteAccountRequest` | Permanently erases a user account and reassigns content to an erased sentinel. |
| `SuspendAccountOperation` | `SuspendAccountRequest` | Suspends an account for moderation review; requires `ADMIN`. |
| `UnsuspendAccountOperation` | `UnsuspendAccountRequest` | Restores a suspended account; requires `ADMIN`. |
| `PurgeErasedContentOperation` | `PurgeErasedContentRequest` | Hard-deletes content owned by an erased sentinel and removes the sentinel; requires `ADMIN`. |
| `DeriveTagsOperation` | `DeriveTagsRequest` | Produces non-persistent AI tag suggestions for editor content; requires `ADMIN`. |

## Notes

- `OtpRequestOperation` intentionally returns the same success message whether or not email delivery
  actually succeeded, so it does not leak account existence.
- `DeleteAccountOperation` and `ExportUserDataOperation` allow self-service for the current user and
  broader review/export for admins.
- `DeriveTagsOperation` is an editor helper for the admin UI; it is not a public chat command.
- These operations are usually reached through `service-blog` HTTP controllers rather than through
  IRC, Slack, or other text protocols.

## Outgoing Links

Once a post is live (published, or a scheduled post once its time comes) and again after each edit, a background pass in lib-blog (`OutgoingLinksTickListener`, every minute) handles its links off the site, once each:

- **Mentions (#112):** each linked page is told of the mention from the post's public address (`BLOG_BASE_URL/posts/<canonical slug>`): by Webmention when it names an endpoint (a `Link` header, then the first `<link>`/`<a>` with `rel="webmention"`), otherwise by Pingback when it names a server (`X-Pingback`, `<link rel="pingback">`), otherwise not at all. A failure on the way or a 5xx is tried twice more. Not sent while `BLOG_BASE_URL` is localhost or a private address.
- **Autosubscribe (#128):** each site linked is offered once to the RSS reader, which follows its site feed (see service-rss's README).

What was handled is kept in the post's `metadata`: `mentioned` (links), `feedsChecked` (hosts) and `linksCheckedThrough` (the update handled), whatever came of each, so nothing is sent twice; it's written without touching `updatedAt`. Posts already live when this arrived (migration V51) are a baseline: when one is next due, its links are recorded without anything being sent. Every fetch goes through lib-core's `GuardedFetcher`, so a post's links never reach an internal address.

Visibility is the log, one line per link or site: `Mention <target> from <source>: webmention 202` / `pingback ok` / `no endpoint` / `refused: ...` / `failed: ...`, and `Autosubscribe <host> from <source>: ...`. Settings are `streampack.blog.outgoing.*` (`BLOG_MENTIONS_ENABLED`, `BLOG_AUTOSUBSCRIBE_ENABLED`).
