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
