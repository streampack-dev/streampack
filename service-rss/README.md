# service-rss

`service-rss` manages external RSS and Atom sources for Streampack.

It covers:

- feed discovery and registration
- destination subscriptions
- feed polling in bounded, spread-out batches with backoff, and new-entry notifications
- OPML export and import for the registered feed catalog
- following the sites published posts link to (autosubscribe, #128)
- admins' ratings of items (RATES, MIGHT, DULL), and the model's hidden guess at them (#187)

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
- `PUT|DELETE /admin/rss/items/{id}/rating`, `GET /admin/rss/items`, `GET /admin/rss/ratings.csv`,
  `GET /admin/rss/rating-stats`, `POST /admin/rss/rating-guesses/run`
  Admins' ratings of items, and the model's hidden guess. See [Item Ratings](#item-ratings) and
  [Admin Feed Item Ratings](../docs/reference/blog-http-api.md#admin-feed-item-ratings).

## Feed Tags

Entries keep their own tags (RSS `<category>`, Atom `<category term>`), as written and normalized,
in `rss_entry_category`, on every poll and when a feed is registered, while they're in the feed.
They map onto BCN's tags through lib-taxonomy's `TagVocabulary` when entries are read: a tag or an
alias maps, a stoplisted term (feed boilerplate such as `uncategorized` and `featured` is stoplisted by
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

## Item Ratings

The annotation phase of picking items worth an article (#16, #187): admins rate items `RATES`
(worth writing about), `MIGHT` or `DULL`, and a model's guess is measured against them before
anything is shown or suggested. Ratings are in `rss_item_rating`, one per item, with every change
in `rss_item_rating_history`; none of it is public, and `/rss/items` is unchanged.

**The model's hidden guess** is off unless `streampack.rss.rating.model-guess`
(`RSS_RATING_MODEL_GUESS=true`). Then `RssRatingGuessTickListener` runs a pass once a day (ten
minutes after startup, then every `guess-interval`), off the tick thread, as the moderation review
does; an admin can run one now with `POST /admin/rss/rating-guesses/run`. A pass:

1. takes the items received in the last `guess-lookback` (two days) with no guess, at most
   `max-items-per-run` (200), leaving out the ones shown as examples;
2. finds each one's text: the feed's own full content (RSS `content:encoded`, Atom `<content>`),
   kept as plain text in `rss_entry_text` as the item is stored; else the article page's, fetched
   once through `GuardedFetcher` (public addresses, its timeouts, the bot's user agent, no retry)
   and extracted with lib-core's `ArticleText`, then kept (or the fact it had none); else the
   summary; else nothing but the title. Each is cut at a word to 4,000 characters;
3. sends them to the moderation model (`AI_MODERATION_MODEL`, never asked to think), `chunk-size`
   (20) to a call, with structured output: `{itemId, label, confidence, reason}` per item;
4. stores each usable answer in `rss_item_guess` with the model and the text's source
   (`content`, `page`, `summary` or `title`). A bad label, a confidence outside 0..1, an item it
   wasn't asked about, or no answer at all is logged and skipped; the item is tried again in a
   later pass while it's still in the lookback.

The system prompt is a short rubric (surprise, craft, delight, depth, a story worth telling, versus
release notes, marketing and another tutorial) and examples: the editor's ten most recent RATES and
five most recent DULL, with title, feed and summary. Until there are `min-rated-examples` (3) RATES,
`streampack.rss.rating.seed-examples` are added (Pong Wars; the Total Annihilation rewrite; a point
release; a tutorial or marketing). It's the same for every chunk and every pass until the editor
rates more, so prompt caching can serve it; the items are the user message. At 15 to 25 items a day
a pass is one plain call; should volume grow, the Message Batches API (asynchronous, half the price)
is the upgrade. Mind `AI_MAX_TOKENS`: a chunk's answer is about 40 tokens an item.

The guess is never shown where items are rated (`GET /admin/rss/items` carries ratings only), so it
can't bias them: only the CSV export and `GET /admin/rss/rating-stats` read it. Nothing about it
blocks polling.

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
