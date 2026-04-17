# Use RSS Feeds

This guide covers the user-facing RSS workflow in the bundled infobot.

`service-rss` lets you:

- register feeds
- subscribe destinations
- inspect subscriptions
- unsubscribe or remove feeds

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
- Duplicate guid entries in one upstream fetch are ignored.
