# operation-urltitle

`operation-urltitle` fetches page titles for URLs seen in conversation.

## Operations

| Operation | Command | Purpose |
|-----------|---------|---------|
| `UrlTitleOperation` | Ambient text containing URLs | Fetches HTML titles and emits non-redundant titles. |
| `ManageIgnoredHostsOperation` | `url ignore list`, `url ignore add <entry>`, `url ignore delete <entry>` | Manages the ignore list. |

## Behavior

`UrlTitleOperation` is unaddressed and only runs for protocols enabled in `UrlTitleProperties`. It emits nothing for:

- URLs covered by the ignore list;
- titles that are too similar to the URL (`similarity-threshold`);
- sign-in and bot-check pages (see below).

The module uses operation group `urltitle`.

### Ignore-list entries

An entry is one of:

| Form | Example | Covers |
|------|---------|--------|
| Host | `repopack.com` | `repopack.com` and `www.repopack.com`, not `docs.repopack.com` |
| Host and path prefix | `repopack.com/project` | `repopack.com/project` and everything below it; not `/projects` or `/blog/...` |
| Subdomains | `*.repopack.com` | `repopack.com`, `www.repopack.com`, `docs.repopack.com`, `a.b.repopack.com` |
| Subdomains and path | `*.repopack.com/project` | `/project/...` on the bare host or any subdomain |

Path prefixes match whole segments: `/project` covers `/project` and `/project/x`, never `/projects`.

`*.host` deliberately covers the bare host too. Someone writing `*.repopack.com` means "anything on that site", and since `www.` is already folded into the bare host, leaving the bare host out would only surprise. Use a plain host entry to cover just the one host.

Entries are normalized when added or deleted: lower-cased, with any scheme (`https://`), leading `www.`, query, fragment and trailing slash removed. `url ignore add https://www.Repopack.com/Project/` stores `repopack.com/project`, and `url ignore list` shows entries in that stored form. Paths are matched ignoring case. A wildcard needs a real domain under it (`*.com` is rejected), and entries are limited to 255 characters.

Defaults (`streampack.urltitle.default-ignored-hosts`) are seeded on startup: `bpa.st`, `dpaste.com`, `pastebin.com`, `pastebin.org`, `twitter.com`, `x.com`. Host entries stored before path and wildcard support keep working unchanged.

### Suppressed titles

A title from a sign-in or bot-check page describes the wall, not the link, so it is never emitted. A title is suppressed when, ignoring case, the whole title or its first or last part around a site-name separator (`·`, `|`, `—`, `–`, or ` - ` with spaces) is one of:

`Sign in`, `Log in`, `Login`, `Login required`, `Sign in to continue`, `Access denied`, `Forbidden`, `Just a moment...` (or with `…`), `Attention Required!`, `Are you a robot?`

So "Repopack · Sign in", "Sign in · GitHub" and "Attention Required! | Cloudflare" are dropped, while "Signing in with passkeys, explained" is reported. The list is configurable as `streampack.urltitle.suppressed-titles`; setting it replaces the defaults, so include them if you only mean to add phrases.

## Permissions

- `url ignore list` is open to anyone.
- `url ignore add` and `url ignore delete` require `ADMIN`, since an entry silences titles in every channel.

## Example Flows

- Paste a URL in chat:
  the operation will emit a title if the URL is not ignored and the title is neither redundant nor a sign-in wall
- View ignored entries:
  `url ignore list`
- Suppress a noisy host (admin):
  `url ignore add example.com`
- Suppress one area of a site (admin):
  `url ignore add repopack.com/project`
- Suppress a site and all its subdomains (admin):
  `url ignore add *.example.com`
