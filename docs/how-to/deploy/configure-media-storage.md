# Configure Media Storage and the Podcast Feed

A post can carry a recording of itself (#196): an admin uploads it, readers play it from the API,
and the feeds offer it as an enclosure. `/podcast.xml` lists the posts that have one, for podcast
apps. The files are kept on disk, in a directory you mount.

Media is off until that directory is configured. While it's off, uploads answer `503` saying so,
`/media/…` answers `404`, posts list no versions, the feeds carry no enclosures, and `/podcast.xml`
is a podcast with no episodes.

## Turn It On

Set the directory, and mount a writable volume there:

```text
STREAMPACK_MEDIA_DIR=/opt/streampack/media
```

The image creates `/opt/streampack/media` and declares it a volume, but doesn't set the variable,
so a deployment chooses. With Docker Compose:

```yaml
services:
  server-streampack:
    environment:
      STREAMPACK_MEDIA_DIR: /opt/streampack/media
    volumes:
      - /srv/streampack/media:/opt/streampack/media
```

The repository's own `docker-compose.yml` does this with a named volume, `media`.

If the variable is set but the directory can't be created or written, the server doesn't start,
and says which directory and why. It doesn't run with uploads that would fail one by one.

The process writes there as the user it runs as (root, in the image), so a bind-mounted host
directory must be writable by that user.

## Limits

| Variable | Default | Meaning |
|----------|---------|---------|
| `STREAMPACK_MEDIA_MAX_SIZE` | `100MB` | The largest file an upload may carry. The server's multipart limits follow it (a request may be a megabyte more, for its other fields). |
| `STREAMPACK_MEDIA_CACHE_MAX_AGE` | `PT5M` | How long browsers and caches may keep a file before asking again. A replaced file keeps its address but not its ETag, so it's fetched again after this. |

There is no cap on total storage. The files aren't in the database, so a database backup doesn't
hold them: back up the media directory, or volume, beside it (see
[Back Up and Restore PostgreSQL](backup-and-restore-postgres.md)). A post's versions refer to their
files by keys the server generated; restoring one without the other leaves versions whose media
answers `404`.

A reverse proxy in front of the API must let an upload through: nginx's `client_max_body_size`
must be at least `STREAMPACK_MEDIA_MAX_SIZE` plus a megabyte (see
[Configure a Reverse Proxy](configure-reverse-proxy.md)). It should also pass `Range` and
`If-None-Match` through, as nginx does by default, so players can seek.

## Addresses

Media is served by the API itself, at `BASE_URL/media/posts/{postId}/audio`, so `BASE_URL` must be
the API's public origin (`https://api.bytecode.news`), not the site's. The post API and the feeds
give that address, which stays the same when a recording is replaced. Front ends that proxy the
feeds must leave `/media/` addresses as they are.

## The Podcast Feed

`/podcast.xml` is `/feed.xml` limited to the posts with a recording, with the `itunes:` tags podcast
apps read. Its channel is configured with:

| Variable | Default | Meaning |
|----------|---------|---------|
| `PODCAST_TITLE` | `BLOG_SITE_NAME` | The show's title. |
| `PODCAST_DESCRIPTION` | "Posts from *site*, read aloud" | The show's description and `itunes:summary`. |
| `PODCAST_AUTHOR` | none | `itunes:author`. |
| `PODCAST_IMAGE` | none | The artwork's absolute URL (`itunes:image`). Apple asks for a square JPEG or PNG, 1400 to 3000 pixels. |
| `PODCAST_CATEGORY` | none | `itunes:category`, as Apple names it, with an optional subcategory after a slash: `Technology`, or `News/Tech News`. |
| `PODCAST_EXPLICIT` | `false` | `itunes:explicit`. |
| `PODCAST_OWNER_NAME`, `PODCAST_OWNER_EMAIL` | none | `itunes:owner`: who directories contact about the show. Apple mails the address to confirm a submission. |
| `PODCAST_LANGUAGE` | `en-us` | The feed's language. |

Each is also read as `STREAMPACK_BLOG_PODCAST_*` (for example `STREAMPACK_BLOG_PODCAST_TITLE`).

Apple Podcasts and Spotify list a show from its feed: submit `https://<site>/podcast.xml` (or the
API's own address for it), and they fetch the audio from the API.

See [Post Versions and Media](../../reference/blog-http-api.md#post-versions-and-media) for the
endpoints.
