# Configure a Reverse Proxy

Terminate TLS at a reverse proxy and forward traffic to the deployed containers or host ports.

## nginx

```nginx
server {
    server_name api.example.com;

    client_max_body_size 1g;

    location / {
        proxy_pass http://127.0.0.1:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
```

`client_max_body_size` matters if this nginx instance also fronts Nexus Docker pushes, and for
uploads of posts' recordings: it must be at least `STREAMPACK_MEDIA_MAX_SIZE` (100 MB by default)
plus a megabyte. Media under `/media/` is served with `Range` support; nginx passes `Range` and
`If-None-Match` through as it is (see [Configure Media Storage](configure-media-storage.md)).

The admin web console's stream (`GET /admin/console/stream`) is a long-lived server-sent event
response. Its responses say `X-Accel-Buffering: no`, which nginx honours, but a location of its own
makes it explicit and gives it a read timeout longer than the console's heartbeat (15 seconds by
default):

```nginx
    location /admin/console/stream {
        proxy_pass http://127.0.0.1:8080;
        proxy_http_version 1.1;
        proxy_set_header Connection "";
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_buffering off;
        proxy_cache off;
        proxy_read_timeout 1h;
    }
```

The console's streams are held in memory by the backend, so it must run as a single instance.

## Caddy

Caddy's `reverse_proxy` sends a `text/event-stream` response on as it arrives and has no read
timeout by default, so the console's stream needs nothing beyond the usual. If `encode` is on for
the site, keep it off the stream, since compressing it buffers it.

Caddy sets no limit on a request body and passes it on as it arrives, so a recording's upload goes
through as it is. A `request_body` limit on the upload paths holds it to `STREAMPACK_MEDIA_MAX_SIZE`
plus a megabyte at the proxy too: uploads are `POST /admin/posts/{id}/versions/{kind}`. `Range` and `If-None-Match` reach `/media/` unchanged.

```caddyfile
api.example.com {
    # Only if you compress at all: everything but the console's stream.
    @compressible not path /admin/console/stream
    encode @compressible gzip zstd

    # Recordings (optional): a limit just over STREAMPACK_MEDIA_MAX_SIZE (100 MB by default).
    @upload path /admin/posts/*/versions/*
    request_body @upload {
        max_size 101MB
    }

    reverse_proxy 127.0.0.1:8080
}
```

## Apache httpd

With `mod_proxy`, `mod_proxy_http` and `mod_headers`:

- **The console's stream** gets `flushpackets=on`, so each event is passed on as it comes, a
  timeout longer than the heartbeat, and no compression.
- **Recording uploads** get a longer timeout, since the default is a minute. Apache passes the body
  on as it arrives. Its default `LimitRequestBody` (1 GB since 2.4.54) is already over 100 MB, but
  setting it holds the upload paths to the API's limit.
- **Order:** each `ProxyPass` and `ProxyPassMatch` must come before the catch-all `/`, since Apache
  takes the first that matches.

```apache
<VirtualHost *:443>
    ServerName api.example.com
    ProxyPreserveHost On
    RequestHeader set X-Forwarded-Proto "https"

    # The admin console's stream: flushed per event, a long timeout, never compressed.
    ProxyPass        /admin/console/stream http://127.0.0.1:8080/admin/console/stream flushpackets=on timeout=3600
    ProxyPassReverse /admin/console/stream http://127.0.0.1:8080/admin/console/stream
    <Location /admin/console/stream>
        SetEnv no-gzip 1
    </Location>

    # Recordings: up to STREAMPACK_MEDIA_MAX_SIZE plus a megabyte, with time to arrive.
    ProxyPassMatch "^/admin/posts/([^/]+)/versions/([^/]+)$" "http://127.0.0.1:8080/admin/posts/$1/versions/$2" timeout=600
    <LocationMatch "^/admin/posts/[^/]+/versions/[^/]+$">
        LimitRequestBody 105906176
    </LocationMatch>

    ProxyPass        / http://127.0.0.1:8080/
    ProxyPassReverse / http://127.0.0.1:8080/
</VirtualHost>
```

If UI and backend are separate hosts or containers, proxy them as separate virtual hosts. The browser-facing UI URL should use `BLOG_BASE_URL`; the server/API URL should use `BASE_URL`.
