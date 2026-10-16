# Run the Published Image with Docker Compose

Releases of `server-streampack` are published as images for linux/amd64 and linux/arm64, and pull without signing in. The amd64 image runs on any x86-64 processor, including virtual machines with a generic CPU type:

```text
nexus.streampack.dev/images/server-streampack:<version>
```

The registry lists them at <https://nexus.streampack.dev/v2/images/server-streampack/tags/list>. Use a version number; `latest` isn't kept current.

The repository's own `docker-compose.yml` builds the image from source, for development. To run a release, use a compose file of your own, like the one below. The [setup tool](https://github.com/streampack-dev/setup) writes this same file, with the `.env` beside it. It works with Docker Compose and with Podman's podman-compose (1.2.0 or later, which reads `.env` as Docker Compose does).

## The Compose File

In a directory of its own, say `/srv/streampack`, as `compose.yaml`:

```yaml
services:
  server-streampack:
    image: nexus.streampack.dev/images/server-streampack:${STREAMPACK_VERSION:?set STREAMPACK_VERSION in .env}
    restart: unless-stopped
    env_file: .env
    environment:
      DB_URL: jdbc:postgresql://db:5432/streampack
      DB_USERNAME: streampack
      STREAMPACK_MEDIA_DIR: /opt/streampack/media
      STREAMPACK_GENERATIVE_PROMPT_DIR: /opt/streampack/generative
      # The setup tool reads this with `docker compose exec`; it never leaves the container.
      STREAMPACK_LOCAL_ADMIN_TOKEN_FILE: /run/streampack/admin-token
    depends_on:
      db:
        condition: service_healthy
    # The API, for a reverse proxy on the same box. Use "8080:8080" to reach it from elsewhere.
    ports:
      - "127.0.0.1:8080:8080"
    # z lets the container at the host's directories under SELinux (Fedora, RHEL, Rocky); without
    # SELinux it does nothing.
    volumes:
      # Posts' recordings, for the audio streams: files on the host, beside .env.
      - ./media:/opt/streampack/media:z
      - ./generative:/opt/streampack/generative:ro,z
      # Extension jars and extra classpath files, such as banner.txt; see "Extend the Server with Jars".
      - ./ext:/opt/streampack/ext:ro,z
    tmpfs:
      - /run/streampack:mode=0700
    # For the stdin console (CONSOLE_ENABLED=true); see "Use the Console in Docker".
    stdin_open: true
    tty: true

  db:
    # The full name: Podman won't guess a registry for a short one without asking.
    image: docker.io/library/postgres:18
    restart: unless-stopped
    environment:
      POSTGRES_DB: streampack
      POSTGRES_USER: streampack
      POSTGRES_PASSWORD: ${DB_PASSWORD:?set DB_PASSWORD in .env}
    volumes:
      # postgres:18 keeps its data under /var/lib/postgresql/18/docker.
      - pgdata:/var/lib/postgresql/18/docker
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U streampack -d streampack"]
      interval: 5s
      retries: 12

volumes:
  pgdata:
```

## The `.env`

Beside it, readable by you alone (`chmod 600 .env`):

```dotenv
STREAMPACK_VERSION=<a version from the tag list>
DB_PASSWORD=<openssl rand -base64 24>
JWT_SECRET=<openssl rand -base64 48>
ADMIN_EMAIL=you@example.com
# The deployment's name; also the default for the site's name and the IRC identity.
STREAMPACK_NAME=nevet
BASE_URL=https://api.example.com
BLOG_BASE_URL=https://www.example.com

# The adapters to run; each is set up afterwards at the console.
MATTERMOST_ENABLED=true
GITLAB_ENABLED=true
# Encrypts stored GitLab webhook secrets: openssl rand -hex 32
GITLAB_WEBHOOK_SECRET_KEY=<openssl rand -hex 32>
```

Every variable is described in [Environment Variables](../../reference/environment-variables.md). Credentials for the integrations (`MATTERMOST_<NAME>_TOKEN` and the like) are added later; see [Move Credentials into the Environment](move-credentials-into-the-environment.md).

## Start It

```bash
mkdir -p generative ext media
docker compose pull
docker compose up -d
curl -fsS http://localhost:8080/features
```

`/features` answers once the server is ready. `docker compose logs -f server-streampack` shows it starting, and says `Superadmin account created for you@example.com` the first time.

## Change Settings

Edit `.env`, then `docker compose up -d`. `docker compose restart` keeps the old environment.

## Back Up

Back up the database and `media/` together: the database refers to the recordings in `media/` by name. See [Back Up and Restore PostgreSQL](backup-and-restore-postgres.md).

On Linux, the files the server writes into `media/` belong to root, because the server runs as root in its container. Read them as root, or with `sudo`, to back them up.

## Upgrade

Set `STREAMPACK_VERSION` to the new version, then `docker compose pull && docker compose up -d`. Database migrations run at startup. Back up first: see [Back Up and Restore PostgreSQL](backup-and-restore-postgres.md).
