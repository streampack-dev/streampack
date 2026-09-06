# Environment Variables

This reference covers the variables commonly used by `server-streampack`.

| Variable | Required | Description |
|----------|----------|-------------|
| `DB_URL` | Yes | JDBC URL for PostgreSQL. |
| `DB_USERNAME` | Yes | PostgreSQL username. |
| `DB_PASSWORD` | Yes | PostgreSQL password. |
| `JWT_SECRET` | Yes | JWT signing secret. Must be strong and deployment-specific. Startup fails when it is unset (with enforcement on) or set to a placeholder such as `change-me`. |
| `BASE_URL` | Production | Public backend URL. |
| `BLOG_BASE_URL` | Production | Public frontend/site URL. |
| `STREAMPACK_SECURITY_ENFORCE_EXTERNAL_SECRETS` | Optional | Defaults to `true`. Fails startup when required secrets are missing or use insecure defaults. Set to `false` only for local development. |
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
| `GITHUB_WEBHOOK_SECRET_KEY` | Optional | Key that encrypts stored GitHub webhook secrets. Required before any repository can be switched to webhook delivery; placeholders such as `change-me` are rejected at startup. |
| `GITHUB_<OWNER>_<REPO>_TOKEN` | Per repository | API token for a watched github.com repository that needs authenticated access, e.g. `GITHUB_STREAMPACK_DEV_STREAMPACK_TOKEN`. `github add owner/repo <token>` stores a literal and names this variable; on the next restart the literal is replaced by an `env://` reference and startup fails until the variable is set (with enforcement on). `github add owner/repo env://KEY` references a variable directly. |
| `GITHUB_<HOST>_<OWNER>_<REPO>_TOKEN` | Per repository | The same, for a repository on an instance other than github.com, e.g. `GITHUB_GHE_EXAMPLE_COM_OWNER_REPO_TOKEN` for `github add owner/repo <token> on ghe.example.com`. |
| `GITHUB_INSTANCE_<HOST>_TOKEN` | Per instance | Default API token for every repository on a registered GitHub instance that has no token of its own, e.g. `GITHUB_INSTANCE_GHE_EXAMPLE_COM_TOKEN` for `github instance add https://ghe.example.com <token>`. Externalized and enforced at startup the same way. |
| `GITHUB_WEBHOOK_BASE_URL` | Optional | Public webhook base URL. |
| `GITLAB_ENABLED` | Optional | Enables GitLab project watching (`service-gitlab`). Off by default; when off the module registers no commands, routes, or polling. |
| `GITLAB_WEBHOOK_SECRET_KEY` | Optional | Key that encrypts stored GitLab webhook secret tokens. Required before any project can be switched to webhook delivery; placeholders such as `change-me` are rejected at startup. |
| `GITLAB_WEBHOOK_BASE_URL` | Optional | Public webhook base URL for GitLab; falls back to `BASE_URL`. |
| `GITLAB_POLL_INTERVAL` | Optional | Polling interval for GitLab projects, ISO-8601 duration (default `PT60M`). |
| `GITLAB_WEBHOOK_DEDUPE_TTL` | Optional | How long GitLab delivery UUIDs are remembered for deduplication (default `PT6H`). |
| `GITLAB_<PATH>_TOKEN` | Per project | API token for a watched gitlab.com project, with `/` in the path flattened to `_`, e.g. `GITLAB_GROUP_SUBGROUP_PROJECT_TOKEN`. Externalized and enforced at startup like GitHub tokens. |
| `GITLAB_<HOST>_<PATH>_TOKEN` | Per project | The same, for a project on a self-hosted instance, e.g. `GITLAB_GITLAB_EXAMPLE_COM_GROUP_PROJECT_TOKEN`. |
| `GITLAB_INSTANCE_<HOST>_TOKEN` | Per instance | Default API token for every project on a registered GitLab instance that has no token of its own. |
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
