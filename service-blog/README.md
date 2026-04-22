# service-blog

`service-blog` is the HTTP-facing blog and account adapter for Streampack.

It owns:

- public and admin blog HTTP controllers
- OTP-based sign-in and token refresh
- account export, erasure, suspension, and unsuspension
- editor-side AI tag derivation

Most operations in this module are typed request handlers used by HTTP controllers rather than
chat-facing bot commands.

## Public HTTP Endpoints

| Endpoint | Purpose |
|----------|---------|
| `GET /posts` | Lists published posts, optionally filtered by `category` or `tag`. |
| `GET /posts/search?q=...` | Searches published posts. |
| `GET /posts/popular` | Lists popular published posts, defaulting to `size=3`. |
| `GET /posts/{year}/{month}/{slug}` | Reads a post by slug and records an access event. |
| `GET /posts/{id}` | Reads a post by UUID and records an access event. |
| `POST /posts/{id}/access` | Records UI-driven post access without returning post content. |
| `GET /pages/{slug}` | Reads a system page from the `_pages` category. |

`GET /posts/popular` is intended for compact UI sections such as "popular posts" or "popular pages."
It returns the same `ContentListResponse` shape as the normal post listing, ordered by decayed
`blog.post` / `hit` temperature.

## Operations

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
