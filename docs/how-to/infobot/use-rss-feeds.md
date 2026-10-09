# Use RSS Feeds

This guide covers the user-facing RSS workflow in the bundled infobot.

`service-rss` lets you:

- register feeds
- subscribe destinations
- inspect subscriptions
- unsubscribe or remove feeds
- export or import the registered feed catalog over the admin HTTP API

## 1. Add a Feed

Direct feed URL:

```text
feed add https://ziglang.org/news/index.xml
```

Site URL with autodiscovery:

```text
feed add https://ziglang.org/news/
```

Autodiscovery first tries standard feed metadata, then falls back to other feed hints such as:

- feed-like anchor links
- body wording that strongly suggests an RSS/Atom link
- common paths like `/feed.xml` or `/index.xml`

## 2. Subscribe the Current Destination

```text
feed subscribe https://ziglang.org/news/
```

The current provenance becomes the subscription target.

## 3. Subscribe an Explicit Target

```text
feed subscribe https://ziglang.org/news/ to irc://libera/%23zig
```

Use this when managing subscriptions for a different destination than the one you are currently in.

## 4. Inspect Subscriptions

Current destination:

```text
feed subscriptions
```

Explicit destination:

```text
feed subscriptions for irc://libera/%23zig
```

## 5. Unsubscribe

```text
feed unsubscribe https://ziglang.org/news/
feed unsubscribe https://ziglang.org/news/ to irc://libera/%23zig
```

## 6. Remove a Feed

```text
feed remove https://ziglang.org/news/
```

This deactivates the feed and any active subscriptions attached to it.

## Permissions

- `feed add`, `feed subscribe`, `feed unsubscribe`, and `feed remove` require `ADMIN`
- `feed list` and `feed subscriptions` are readable without admin privileges

## Practical Notes

- If autodiscovery fails, you can still add the direct feed URL.
- If a feed is already registered, re-adding it is harmless.
- Polling stores a baseline of entries and only notifies on new items.
- Feeds are polled in small batches spread over time, not all at once: every 90 seconds the poller takes the five feeds that have been due longest, and each polled feed is next due an hour later (all configurable, see [Environment Variables](../../reference/environment-variables.md)). A feed that fails to fetch is retried with doubling delays up to a day, so a dead feed never ties up the poller.
- Duplicate guid entries in one upstream fetch are ignored.
- Entries keep the feed's own tags (RSS `<category>`, Atom `<category term>`), mapped onto the site's tags where the tag vocabulary knows them. A feed tag it doesn't know waits until it's on three entries across two feeds, then becomes a tag; admins can decide one sooner with `feed tags` and `feed tag map|ignore|create`. See [Tag Names](../../reference/tags.md#feed-tags).
- Admins can rate items `RATES`, `MIGHT` or `DULL` (through the front ends, or `PUT /admin/rss/items/{id}/rating`), to learn what's worth writing about. Ratings are never public. With `RSS_RATING_MODEL_GUESS=true`, a model guesses each new item's rating once a day, judging the feed's full content when it gives one, else the article page, else the summary; the guess stays hidden from the rating UI and is measured against the ratings in `GET /admin/rss/rating-stats`. See [Admin Feed Item Ratings](../../reference/blog-http-api.md#admin-feed-item-ratings).

## OPML Import and Export

The bundled server also exposes admin HTTP endpoints for catalog portability:

- `GET /admin/rss/opml`
- `POST /admin/rss/opml/import`

The import endpoint tries OPML first, then falls back to plain text URL extraction. That means an operator can paste content like:

```text
Block 1
https://foo.bar.com/rss.xml
https://bar.foo.com/feed.xml
Block 2
https://baz.com/rss.xml
```

and only the URL lines will be treated as feed candidates.
