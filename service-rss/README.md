# service-rss

`service-rss` manages external RSS and Atom sources for Streampack.

It covers:

- feed discovery and registration
- destination subscriptions
- feed polling and new-entry notifications
- OPML export and import for the registered feed catalog

## Operations

| Operation | Command | Purpose |
|-----------|---------|---------|
| `AddFeedOperation` | `feed add <url>` | Registers a feed directly or discovers one from a site URL; requires `ADMIN`. |
| `FeedManagementOperation` | `feed list`, `feed subscribe ...`, `feed unsubscribe ...`, `feed subscriptions`, `feed remove ...` | Lists feeds, manages subscriptions, and deactivates feeds. |

## User Commands

### Register a Feed

```text
feed add https://example.com/feed.xml
feed add https://example.com/blog/
```

If the URL already points at a feed, Streampack parses it directly.

If the URL points at a site page, Streampack tries autodiscovery through:

- standard `<link rel="alternate" ...>` metadata
- feed-like anchor links
- body wording that strongly suggests a feed link
- common fallback paths such as `/feed.xml` and `/index.xml`

### List Registered Feeds

```text
feed list
```

This shows known feed titles and URLs. Inactive feeds are marked as inactive.

### Subscribe the Current Destination

```text
feed subscribe https://example.com/feed.xml
```

Without an explicit target, the current channel or destination is used.

### Subscribe an Explicit Destination

```text
feed subscribe https://example.com/feed.xml to irc://libera/%23java
```

### Show Subscriptions

```text
feed subscriptions
feed subscriptions for irc://libera/%23java
```

### Unsubscribe

```text
feed unsubscribe https://example.com/feed.xml
feed unsubscribe https://example.com/feed.xml to irc://libera/%23java
```

### Remove a Feed

```text
feed remove https://example.com/feed.xml
```

This deactivates the feed and any active subscriptions attached to it.

## Permissions

- `feed add`, `feed subscribe`, `feed unsubscribe`, and `feed remove` require `ADMIN`
- `feed list` and `feed subscriptions` are readable without admin privileges

## HTTP API

`service-rss` also exposes admin HTTP endpoints for feed-catalog portability:

- `GET /admin/rss/opml`
  Exports all active registered feeds as OPML.
- `POST /admin/rss/opml/import`
  Imports feeds from either:
  - valid OPML containing `xmlUrl` outlines
  - plain text where each line may or may not be a URL

The import endpoint intentionally tolerates messy operator input. For example:

```text
Block 1
https://foo.bar.com/rss.xml
https://bar.foo.com/feed.xml
Block 2
https://baz.com/rss.xml
```

The non-URL lines are ignored; the URL lines are treated as feed candidates. OPML is still attempted first when the payload is valid XML/OPML.

## Discovery Notes

The discovery path is intentionally pragmatic.

It can handle pages like:

```html
<p>This page is also available as an <a href="/news/index.xml">RSS feed</a>.</p>
```

so slightly broken sites can still be discovered from the page URL rather than requiring a direct feed URL.

## Example Flows

- Add a direct feed:
  `feed add https://ziglang.org/news/index.xml`
- Add a site and let discovery find the feed:
  `feed add https://ziglang.org/news/`
- Subscribe the current channel:
  `feed subscribe https://ziglang.org/news/`
- Inspect subscriptions:
  `feed subscriptions`
- Subscribe another target explicitly:
  `feed subscribe https://example.com/feed.xml to irc://libera/%23news`
- Remove a feed:
  `feed remove https://example.com/feed.xml`
