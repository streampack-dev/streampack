# service-rss

`service-rss` manages external RSS and Atom sources for Streampack.

It covers:

- feed discovery and registration
- destination subscriptions
- feed polling in bounded, spread-out batches with backoff, and new-entry notifications
- OPML export and import for the registered feed catalog
- following the sites published posts link to (autosubscribe, #128)

## Operations

| Operation | Command | Purpose |
|-----------|---------|---------|
| `AddFeedOperation` | `feed add <url>` | Registers a feed directly or discovers one from a site URL; requires `ADMIN`. |
| `FeedManagementOperation` | `feed list`, `feed subscribe ...`, `feed unsubscribe ...`, `feed subscriptions`, `feed remove ...` | Lists feeds, manages subscriptions, and deactivates feeds. |
| `LinkedSiteSubscriptionOperation` | (typed `LinkedSiteSubscriptionRequest`, from the blog) | Follows the site feed of a site a published post links to; see [Autosubscribe](#autosubscribe). |
| `FeedTagOperation` | `feed tags`, `feed tag <name>`, `feed tag map <name> = <tag>`, `feed tag ignore <name>`, `feed tag create <name>` | Lists and decides feed tags the tag vocabulary doesn't know; requires `ADMIN`. See [Feed Tags](#feed-tags). |

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

`service-rss` also exposes HTTP endpoints. For feed-catalog portability, for admins:

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

- `GET /rss/items`, `GET /rss/feeds`
  The stored items, newest first, and the active feeds, for the front ends. Each item carries
  `categories` (its own tags, as the feed wrote them) and `tags` (the BCN tags they map to).
- `GET /admin/rss/tags`, `POST /admin/rss/tags/map`, `/ignore`, `/create`
  Feed tags the vocabulary didn't know, and an admin's decision on one. See
  [Admin Feed Tags](../docs/reference/blog-http-api.md#admin-feed-tags).

## Feed Tags

Entries keep their own tags (RSS `<category>`, Atom `<category term>`), as written and normalized,
in `rss_entry_category`, on every poll and when a feed is registered, while they're in the feed.
They map onto BCN's tags through lib-taxonomy's `TagVocabulary` when entries are read: a tag or an
alias maps, a stoplisted term (feed boilerplate such as `uncategorized` and `blog` is stoplisted by
`V74`) is ignored, and anything else waits in `rss_feed_tag`, counted, until it's on
`streampack.rss.tags.promote-entries` (3) entries across `promote-feeds` (2) feeds; then it's
created as a tag through the vocabulary's create rule. Admins can map, ignore or create one first.
Feed items don't count toward tag counts or the taxonomy. The whole of it is in
[Tag Names](../docs/reference/tags.md#feed-tags).

```text
feed tags
feed tag quarkus
feed tag map golang = go
feed tag ignore rumour
feed tag create helidon
```

## Discovery Notes

The discovery path is intentionally pragmatic.

It can handle pages like:

```html
<p>This page is also available as an <a href="/news/index.xml">RSS feed</a>.</p>
```

so slightly broken sites can still be discovered from the page URL rather than requiring a direct feed URL.

Of the feeds a page advertises with `<link rel="alternate">`, the site's own comes before its comments feed (WordPress article pages advertise both).

Every fetch, discovery and polling alike, goes through lib-core's `GuardedFetcher`: http(s) only, to public addresses only (loopback, private, link-local, so the cloud metadata address, multicast and unspecified are refused, after DNS and on every redirect), with short timeouts and a cap on what's read (10 MiB for a feed). Its settings are `streampack.fetch.*`; `allow-loopback` and `allow-private` are for tests and local development.

## Autosubscribe

When a post is published (or a published post edited), the blog's outgoing-links pass offers each site it links to, once per post, with a `LinkedSiteSubscriptionRequest`. For each:

1. A host in `streampack.rss.autosubscribe.skip-hosts`, or a subdomain of one, is skipped without a fetch: code hosts, video, encyclopedias, documentation and specs by default (`github.com`, `youtube.com`, `wikipedia.org`, `openjdk.org`, `docs.oracle.com`, ...), but not `github.io`, where many personal blogs are.
2. A host we already have a feed on (by the feed's address or its site's) is left alone, without a fetch.
3. Otherwise the page's site feed is discovered (`FeedDiscoveryService.discoverSiteFeed`): as leniently as `feed add`, but never a comments feed, and a feed found by a feed-like link or a guessed path only when the feed's own site is the linked host.
4. A feed found is added as `feed add` would add it; one we have already isn't added again.

Each outcome is a log line on the blog side: `Autosubscribe <host> from <post>: added <feed>` / `already have <feed>` / `skipped (...)` / `no feed`. Turn it off with `BLOG_AUTOSUBSCRIBE_ENABLED=false`.

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
