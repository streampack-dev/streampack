# Deploy `server-streampack`

This tutorial deploys the bundled server distribution from an image you build and publish yourself. It assumes Docker Compose, PostgreSQL, and a reverse proxy on the host.

To run a published release instead, with nothing to build, follow [Deploy with Docker for Mattermost and GitLab](deploy-with-docker-for-mattermost-and-gitlab.md), or set the files up by hand with [Run the Published Image with Docker Compose](../how-to/deploy/run-the-published-image.md).

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
STREAMPACK_VERSION=<version>
DB_PASSWORD=replace-with-a-strong-password
ADMIN_EMAIL=you@example.com
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

Use a compose file that pulls the image you published: [Run the Published Image with Docker Compose](../how-to/deploy/run-the-published-image.md) has one, with `nexus.streampack.dev/images` as the registry. Point its `image:` at your own registry if you pushed elsewhere. (The repository's `docker-compose.yml` builds from source instead, for development.)

```bash
docker compose pull
docker compose up -d
```

Check backend readiness:

```bash
curl -fsS http://localhost:8080/features
```

On first start the log says `Superadmin account created for you@example.com`. That account signs in with a one-time code by mail (step 4), and can give commands at the console; see [Use the Console in Docker](../how-to/deploy/use-the-console-in-docker.md).

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
