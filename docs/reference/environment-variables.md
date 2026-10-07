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
| `GITHUB_<OWNER>_<REPO>_TOKEN` | Per repository | API token for a watched github.com repository that needs authenticated access, e.g. `GITHUB_STREAMPACK_DEV_STREAMPACK_TOKEN`. `github add owner/repo <token>` stores a literal and names this variable; with enforcement on, startup fails until the variable is set (values are never printed), after which the literal is replaced by an `env://` reference. `github add owner/repo env://KEY` references a variable directly. |
| `GITHUB_<HOST>_<OWNER>_<REPO>_TOKEN` | Per repository | The same, for a repository on an instance other than github.com, e.g. `GITHUB_GHE_EXAMPLE_COM_OWNER_REPO_TOKEN` for `github add owner/repo <token> on ghe.example.com`. |
| `GITHUB_INSTANCE_<HOST>_TOKEN` | Per instance | Default API token for every repository on a registered GitHub instance that has no token of its own, e.g. `GITHUB_INSTANCE_GHE_EXAMPLE_COM_TOKEN` for `github instance add https://ghe.example.com <token>`. Externalized and enforced at startup the same way. |
| `GITHUB_WEBHOOK_BASE_URL` | Optional | Public webhook base URL. |
| `GITLAB_ENABLED` | Optional | Enables GitLab project watching (`service-gitlab`). Off by default; when off the module registers no commands, routes, or polling. |
| `GITLAB_WEBHOOK_SECRET_KEY` | Optional | Key that encrypts stored GitLab webhook secret tokens. Required before any project can be switched to webhook delivery; placeholders such as `change-me` are rejected at startup. |
| `GITLAB_WEBHOOK_BASE_URL` | Optional | Public webhook base URL for GitLab; falls back to `BASE_URL`. |
| `GITLAB_POLL_INTERVAL` | Optional | Polling interval for GitLab projects, ISO-8601 duration (default `PT60M`). |
| `GITLAB_WEBHOOK_DEDUPE_TTL` | Optional | How long GitLab delivery UUIDs are remembered for deduplication (default `PT6H`). |
| `GITHUB_POLL_INTERVAL` | Optional | How long after a poll a watched GitHub repository is next due (default `PT60M`). |
| `GITHUB_SCHEDULER_INTERVAL` | Optional | How often the GitHub poller wakes to take a batch of due repositories (default `PT90S`). |
| `GITHUB_POLL_BATCH_SIZE` | Optional | Maximum repositories polled per wake-up, oldest due first (default `5`). |
| `GITHUB_POLL_MAX_BACKOFF` | Optional | Cap on the exponential backoff applied to a repository whose API calls fail (default `P1D`). |
| `GITLAB_SCHEDULER_INTERVAL` | Optional | As above, for GitLab (default `PT90S`). |
| `GITLAB_POLL_BATCH_SIZE` | Optional | As above, for GitLab (default `5`). |
| `GITLAB_POLL_MAX_BACKOFF` | Optional | As above, for GitLab (default `P1D`). |
| `MATTERMOST_ENABLED` | Optional | Enables the Mattermost adapter (`service-mattermost`). Off by default. |
| `MATTERMOST_SIGNAL` | Optional | Signal character that addresses the bot in Mattermost channels (default `!`); overridable per server with `mattermost signal`. |
| `MATTERMOST_RECONNECT_DELAY` | Optional | Delay before reconnecting a dropped Mattermost WebSocket (default `PT15S`). |
| `MATTERMOST_<NAME>_TOKEN` | Per server | Access token for the Mattermost server registered as `<name>`, e.g. `MATTERMOST_WORK_TOKEN`. `mattermost connect` stores a literal; with enforcement on, startup fails until the variable is set (the value is never printed), after which the literal is rewritten to an `env://` reference. |
| `RSS_POLL_INTERVAL` | Optional | How long after a poll a feed is next due, ISO-8601 duration (default `PT1H`). |
| `RSS_SCHEDULER_INTERVAL` | Optional | How often the feed poller wakes to take a batch of due feeds (default `PT90S`). |
| `RSS_POLL_BATCH_SIZE` | Optional | Maximum feeds polled per wake-up, oldest due first (default `5`). |
| `RSS_POLL_MAX_BACKOFF` | Optional | Cap on the exponential backoff a failing feed's next poll is pushed out by (default `P1D`). |
| `BLOG_MENTIONS_ENABLED` | Optional | Tell the pages a published post links to of the mention, by Webmention or Pingback (default `true`). Never sent while `BLOG_BASE_URL` is localhost or a private address. |
| `BLOG_AUTOSUBSCRIBE_ENABLED` | Optional | Subscribe the RSS reader to the site feeds of the sites a published post links to, if not already had (default `true`). The hosts never subscribed to are `streampack.rss.autosubscribe.skip-hosts`. |
| `GITLAB_<PATH>_TOKEN` | Per project | API token for a watched gitlab.com project, with `/` in the path flattened to `_`, e.g. `GITLAB_GROUP_SUBGROUP_PROJECT_TOKEN`. Externalized and enforced at startup like GitHub tokens. |
| `GITLAB_<HOST>_<PATH>_TOKEN` | Per project | The same, for a project on a self-hosted instance, e.g. `GITLAB_GITLAB_EXAMPLE_COM_GROUP_PROJECT_TOKEN`. |
| `GITLAB_INSTANCE_<HOST>_TOKEN` | Per instance | Default API token for every project on a registered GitLab instance that has no token of its own. |
| `ANTHROPIC_API_KEY` | Optional | Anthropic API key. |
| `AI_ENABLED` | Optional | Enables AI-backed features. |
| `AI_MODEL` | Optional | The Anthropic model for AI features: ask, summaries, derived factoids, poetry (default `claude-opus-5-5`). |
| `AI_MODERATION_MODEL` | Optional | A cheaper model for high-volume or background work, such as abuse detection (default `claude-haiku-4-5-20251001`). It shares the timeout, retries and token limit, but not `AI_THINKING` or `AI_EFFORT`, which apply to `AI_MODEL` only (Haiku 4.5 refuses an effort). |
| `AI_MAX_TOKENS` | Optional | The most tokens an answer may have (default `1024`). |
| `AI_TIMEOUT` | Optional | How long one call to the model may take before it fails and is logged, a duration such as `60s` (default `60s`). |
| `AI_MAX_RETRIES` | Optional | How often a failed call is tried again (default `1`). |
| `AI_THINKING` | Optional | Ask the model to reason before answering (adaptive thinking; default `false`). Off sends nothing about thinking, as some models (Opus 5.5) refuse having it disabled. |
| `AI_EFFORT` | Optional | How much effort a model that supports it spends, thinking included: `low`, `medium`, `high`, `xhigh` or `max`. Unset, the model's default. For Opus 5.5, `low` is the least thinking it allows. |
| `STREAMPACK_MODERATION_ENABLED` | Optional | Abuse detection (#150): score public channel messages and run the hourly review (default `true`). It never hides, removes or bans anything; admins act on its reports. The other settings are `STREAMPACK_MODERATION_*` too: `THRESHOLD` (`10`), `HALF_LIFE` (`30m`), `WINDOW` (`1h`), `REVIEW_INTERVAL` (`1h`), `REVIEW_LINES` (`20`), `CONTEXT_LINES` (`3`), `BLOCKED_HOSTS` (comma-separated, none by default), and the word lists `PROFANITY`, `INSULTS` and `SLURS`, which replace the built-in ones. See [The Message Log](../explanation/message-log.md#moderation). |
| `BLOG_SUMMARY_PROMPT` | Optional | System prompt for admins' AI-derived post summaries. Empty uses the built-in stance: lead with the substance, never tease or bury the lede. |
| `IRC_ENABLED` | Optional | Enables IRC adapter. |
| `IRC_IDENTITY` | Optional | The bot's identity, as its CTCP `VERSION` answer (default `Nevet IRC Bridge`). |
| `IRC_ADAPTIVE_SEND_DELAY_ENABLED` | Optional | Spaces outgoing IRC messages adaptively, growing the gap under a backlog (default `true`). |
| `IRC_MIN_SEND_DELAY_MS`, `IRC_MAX_SEND_DELAY_MS` | Optional | Bounds of the adaptive gap (defaults `120` and `1000`). |
| `IRC_SEND_DELAY_RAMP_UP_FACTOR`, `IRC_SEND_DELAY_RAMP_DOWN_FACTOR` | Optional | How fast the adaptive gap grows and shrinks (defaults `1.1` and `0.9`). |
| `IRC_SEND_DELAY_MS` | Optional | Fixed gap between messages when adaptive sending is off (default `900`). |
| `IRC_<NAME>_SASL_ACCOUNT`, `IRC_<NAME>_SASL_PASSWORD` | Per network | SASL credentials for the IRC network registered as `<name>`, e.g. `IRC_LIBERA_SASL_PASSWORD`. `irc connect` stores literals; with enforcement on, startup fails until the variables are set (values are never printed), after which the literals are rewritten to `env://` references. |
| `DISCORD_ENABLED` | Optional | Enables Discord adapter. |
| `DISCORD_APPLICATION_ID` | Optional | Discord application id. |
| `DISCORD_PUBLIC_KEY` | Optional | Discord public key. |
| `DISCORD_BOT_TOKEN` | Optional | Discord bot token. |
| `DISCORD_PERMISSIONS_VALUE` | Optional | Discord permission integer, default `3072`. |
| `SLACK_ENABLED` | Optional | Enables Slack adapter. |
| `SLACK_SIGNAL` | Optional | Signal that addresses the bot in Slack channels (default `!`); overridable per workspace with `slack signal`. |
| `SLACK_<NAME>_BOT_TOKEN`, `SLACK_<NAME>_APP_TOKEN` | Per workspace | Bot (`xoxb-`) and app (`xapp-`) tokens for the Slack workspace registered as `<name>`, e.g. `SLACK_JVM_NEWS_BOT_TOKEN`. Externalized and enforced at startup like Mattermost tokens. |
| `CONSOLE_ENABLED` | Optional | Enables console adapter. |
| `CORS_ORIGINS` | Optional | Comma-separated browser origins trusted for credentialed cross-origin requests; also the origins a cookie-authenticated web console command must come from. |
| `STREAMPACK_WEBCONSOLE_COMMANDS_PER_MINUTE` | Optional | Web console commands an admin may submit a minute (default `30`). The console's other limits are `STREAMPACK_WEBCONSOLE_*` too: `HEARTBEAT` (`15s`), `MAX_STREAM_AGE` (`1h`), `MAX_STREAMS_PER_USER` (`4`), `STREAM_OPENS_PER_MINUTE` (`20`), `QUEUE_EVENTS` (`100`), `QUEUE_BYTES` (`1048576`), `MAX_EVENT_BYTES` (`131072`), `MAX_LINE` (`4096`), `MAX_BODY` (`32768`). |

Each `AI_*` setting is also read as `STREAMPACK_AI_*` (e.g. `STREAMPACK_AI_MODEL`, `STREAMPACK_AI_MODERATION_MODEL`), the property's own name in the environment; where both are set, the `STREAMPACK_AI_*` one wins.
