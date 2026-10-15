# Configure the Runtime Environment

`server-streampack` reads runtime configuration from environment variables. Docker Compose can load them from `.env` at container startup.

Required production values:

| Variable | Purpose |
|----------|---------|
| `JWT_SECRET` | HMAC secret for JWT signing. Must be unique and strong. |
| `DB_URL` | JDBC URL for PostgreSQL. In Compose this is usually `jdbc:postgresql://db:5432/nevet`. |
| `DB_USERNAME` | PostgreSQL username. |
| `DB_PASSWORD` | PostgreSQL password. The repository's `docker-compose.yml` gives it to the database too (default `nevet`, for development only). |
| `ADMIN_EMAIL` | The super-admin's address, created on first start when there's none. Needed for the console and the local admin token. |

Common public URLs:

| Variable | Purpose |
|----------|---------|
| `BASE_URL` | Public backend/API URL. |
| `BLOG_BASE_URL` | Public frontend/site URL. |
| `GITHUB_WEBHOOK_BASE_URL` | Public URL GitHub should call for webhooks. |
| `GITLAB_WEBHOOK_BASE_URL` | Public URL GitLab should call for webhooks; must be HTTPS. |

Mail:

| Variable | Purpose |
|----------|---------|
| `MAIL_ENABLED` | Enables outbound mail. |
| `MAIL_HOST` | SMTP host. Use `host.docker.internal` for a host MTA reachable from Docker. |
| `MAIL_PORT` | SMTP port, usually `25`, `587`, or `465`. |
| `MAIL_USERNAME` | SMTP username, if authentication is enabled. |
| `MAIL_PASSWORD` | SMTP password, if authentication is enabled. |
| `MAIL_AUTH` | Enables SMTP auth. |
| `MAIL_STARTTLS` | Enables STARTTLS. |
| `MAIL_FROM` | Sender address for OTP and notification mail. |

Sign-in:

| Variable | Purpose |
|----------|---------|
| `OTP_MAX_FAILED_ATTEMPTS` | Wrong one-time codes a recipient may present before all of its active codes are expired. Defaults to `5`. |

`define`:

| Variable | Purpose |
|----------|---------|
| `DICTIONARY_TIMEOUT` | How long each dictionary is waited for before the next is asked. Defaults to `3s`. |
| `DICTIONARY_BREAK_FOR` | How long a dictionary that failed is skipped. Defaults to `15m`. |

Channel logs:

| Variable | Purpose |
|----------|---------|
| `STREAMPACK_LOG_ZONE` | The IANA timezone log days are split by, such as `America/New_York`. Defaults to `UTC`; an invalid zone stops startup. |

Optional integrations:

| Variable | Purpose |
|----------|---------|
| `IRC_ENABLED` | Enables IRC adapter. |
| `DISCORD_ENABLED` | Enables Discord adapter. |
| `SLACK_ENABLED` | Enables Slack adapter. |
| `MATTERMOST_ENABLED` | Enables Mattermost adapter. |
| `AI_ENABLED` | Enables AI-backed features. |
| `STREAMPACK_GENERATIVE_PROMPT_DIR` | Filesystem directory for external generative prompt overrides. |
| `STREAMPACK_MEDIA_DIR` | Writable directory for uploaded media, such as posts' recordings. Unset, media is off. See [Configure Media Storage](configure-media-storage.md). |
| `PODCAST_TITLE`, `PODCAST_AUTHOR`, `PODCAST_IMAGE`, `PODCAST_CATEGORY`, `PODCAST_EXPLICIT`, `PODCAST_OWNER_EMAIL` | The podcast feed's channel (`/podcast.xml`). See [Configure Media Storage](configure-media-storage.md#the-podcast-feed). |
| `ANTHROPIC_API_KEY` | Anthropic API key. |
| `GITHUB_WEBHOOK_SECRET_KEY` | Key that encrypts stored GitHub webhook secrets. Needed before enabling webhook delivery. |
| `GITLAB_ENABLED` | Turns on GitLab project watching. |
| `GITLAB_WEBHOOK_SECRET_KEY` | Key that encrypts stored GitLab webhook secret tokens. Needed before enabling GitLab webhook delivery. |
| `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET` | Google OIDC credentials. |
| `GITHUB_CLIENT_ID`, `GITHUB_CLIENT_SECRET` | GitHub OAuth credentials. |

Do not rely on application defaults for production secrets. There is no default for `JWT_SECRET`: the server refuses to start when it is missing or set to a placeholder such as `change-me`, and Compose fails fast when it is unset.

If `STREAMPACK_GENERATIVE_PROMPT_DIR` is set, Streampack will look there for runtime prompt overrides such as `suggest-prompt.txt` or `suggest-prompt.clj`.
