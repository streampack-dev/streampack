# Deploy `server-streampack`

This tutorial deploys the bundled server distribution. It assumes Docker Compose, PostgreSQL, and a reverse proxy on the host.

## 1. Build and Publish the Image

From the Streampack repository:

```bash
just image <version>
```

By default this pushes:

```text
nexus.streampack.dev/images/server-streampack:<version>
```

Set `DOCKER_PUSH_LATEST=true` only when you intentionally want to publish a `latest` tag.

## 2. Create the Runtime Environment

Create a deployment `.env` on the host. Minimum values:

```dotenv
APP_IMAGE_TAG=<version>
DB_PASSWORD=replace-with-a-strong-password
JWT_SECRET=replace-with-a-long-random-secret
BASE_URL=https://api.example.com
BLOG_BASE_URL=https://www.example.com
MAIL_ENABLED=true
MAIL_HOST=host.docker.internal
MAIL_PORT=25
MAIL_FROM=noreply@example.com
```

Generate a JWT secret with:

```bash
openssl rand -base64 48
```

## 3. Start the Services

Use Docker Compose from the deployment project:

```bash
docker compose pull
docker compose up -d
```

Check backend readiness:

```bash
curl -fsS http://localhost:8080/features
```

If the UI is deployed as a peer container, it should call the backend by service name, for example:

```dotenv
UI_API_URL=http://server-streampack:8080
```

If the backend runs on the host outside Docker, use:

```dotenv
UI_API_URL=http://host.docker.internal:8080
```

## 4. Configure Mail

If the container sends mail through host Postfix, Postfix must listen on an address reachable from the Compose network. `127.0.0.1:25` is not reachable from a container.

Find the active Compose subnet:

```bash
docker network inspect deploy_default --format '{{json .IPAM.Config}}'
```

Allow that subnet in Postfix `mynetworks`, then reload Postfix. See [Configure Postfix](../how-to/deploy/configure-email-postfix.md).

## 5. Put a Reverse Proxy in Front

Terminate TLS at nginx, Caddy, Apache httpd, or another proxy. Send API traffic to `localhost:8080` and UI traffic to the UI container or host port.

See [Configure a reverse proxy](../how-to/deploy/configure-reverse-proxy.md).
