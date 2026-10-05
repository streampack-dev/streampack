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

`client_max_body_size` matters if this nginx instance also fronts Nexus Docker pushes.

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

```caddyfile
api.example.com {
    reverse_proxy 127.0.0.1:8080
}
```

## Apache httpd

```apache
<VirtualHost *:443>
    ServerName api.example.com
    ProxyPreserveHost On
    ProxyPass / http://127.0.0.1:8080/
    ProxyPassReverse / http://127.0.0.1:8080/
</VirtualHost>
```

If UI and backend are separate hosts or containers, proxy them as separate virtual hosts. The browser-facing UI URL should use `BLOG_BASE_URL`; the server/API URL should use `BASE_URL`.
