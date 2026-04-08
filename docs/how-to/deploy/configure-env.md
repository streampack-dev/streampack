# Configure the Runtime Environment

`server-streampack` reads runtime configuration from environment variables. Docker Compose can load them from `.env` at container startup.

Required production values:

| Variable | Purpose |
|----------|---------|
| `JWT_SECRET` | HMAC secret for JWT signing. Must be unique and strong. |
| `DB_URL` | JDBC URL for PostgreSQL. In Compose this is usually `jdbc:postgresql://db:5432/nevet`. |
| `DB_USERNAME` | PostgreSQL username. |
| `DB_PASSWORD` | PostgreSQL password. |

Common public URLs:

| Variable | Purpose |
|----------|---------|
| `BASE_URL` | Public backend/API URL. |
| `BLOG_BASE_URL` | Public frontend/site URL. |
| `GITHUB_WEBHOOK_BASE_URL` | Public URL GitHub should call for webhooks. |

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

Optional integrations:

| Variable | Purpose |
|----------|---------|
| `IRC_ENABLED` | Enables IRC adapter. |
| `DISCORD_ENABLED` | Enables Discord adapter. |
| `SLACK_ENABLED` | Enables Slack adapter. |
| `AI_ENABLED` | Enables AI-backed features. |
| `ANTHROPIC_API_KEY` | Anthropic API key. |
| `GITHUB_WEBHOOK_SECRET_KEY` | GitHub webhook signing secret. |
| `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET` | Google OIDC credentials. |
| `GITHUB_CLIENT_ID`, `GITHUB_CLIENT_SECRET` | GitHub OAuth credentials. |

Do not rely on application defaults for production secrets. Compose should fail fast when `JWT_SECRET` is missing.
