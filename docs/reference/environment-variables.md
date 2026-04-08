# Environment Variables

This reference covers the variables commonly used by `server-streampack`.

| Variable | Required | Description |
|----------|----------|-------------|
| `DB_URL` | Yes | JDBC URL for PostgreSQL. |
| `DB_USERNAME` | Yes | PostgreSQL username. |
| `DB_PASSWORD` | Yes | PostgreSQL password. |
| `JWT_SECRET` | Yes | JWT signing secret. Must be strong and deployment-specific. |
| `BASE_URL` | Production | Public backend URL. |
| `BLOG_BASE_URL` | Production | Public frontend/site URL. |
| `STREAMPACK_SECURITY_ENFORCE_EXTERNAL_SECRETS` | Optional | Fails startup when insecure defaults are used. |
| `MAIL_ENABLED` | Optional | Enables outbound email. |
| `MAIL_HOST` | Optional | SMTP host. |
| `MAIL_PORT` | Optional | SMTP port. |
| `MAIL_USERNAME` | Optional | SMTP username. |
| `MAIL_PASSWORD` | Optional | SMTP password. |
| `MAIL_AUTH` | Optional | Enables SMTP authentication. |
| `MAIL_STARTTLS` | Optional | Enables STARTTLS. |
| `MAIL_FROM` | Optional | Sender address. |
| `GOOGLE_CLIENT_ID` | Optional | Google OIDC client id. |
| `GOOGLE_CLIENT_SECRET` | Optional | Google OIDC client secret. |
| `GITHUB_CLIENT_ID` | Optional | GitHub OAuth client id. |
| `GITHUB_CLIENT_SECRET` | Optional | GitHub OAuth client secret. |
| `GITHUB_WEBHOOK_SECRET_KEY` | Optional | GitHub webhook signing secret. |
| `GITHUB_WEBHOOK_BASE_URL` | Optional | Public webhook base URL. |
| `ANTHROPIC_API_KEY` | Optional | Anthropic API key. |
| `AI_ENABLED` | Optional | Enables AI-backed features. |
| `IRC_ENABLED` | Optional | Enables IRC adapter. |
| `DISCORD_ENABLED` | Optional | Enables Discord adapter. |
| `DISCORD_APPLICATION_ID` | Optional | Discord application id. |
| `DISCORD_PUBLIC_KEY` | Optional | Discord public key. |
| `DISCORD_BOT_TOKEN` | Optional | Discord bot token. |
| `DISCORD_PERMISSIONS_VALUE` | Optional | Discord permission integer, default `3072`. |
| `SLACK_ENABLED` | Optional | Enables Slack adapter. |
| `CONSOLE_ENABLED` | Optional | Enables console adapter. |
