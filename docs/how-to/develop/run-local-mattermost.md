# Run a Local Mattermost for Development

`service-mattermost` connects to a Mattermost server as an ordinary account: it reads posts over the server's WebSocket API and replies over REST. The production server you care about may sit on a restricted network, so this page brings up a private Mattermost on your machine and exercises command ingestion and replies end to end against it. Nothing here assumes access to any remote instance.

## 1. Start Mattermost

The repository's `docker-compose.yml` carries a `mattermost` profile using the official preview image, which bundles its own database:

```bash
docker compose --profile mattermost up -d mattermost
```

Open <http://localhost:8065>. The preview image is for evaluation only: it uses known passwords, keeps no durable data across image upgrades, and must not be used in production.

## 2. Create the admin, a team, and the bot account

1. Sign up at <http://localhost:8065>. The first account becomes the system admin.
2. Create a team, for example `dev`.
3. Create the account the bot will use. Either:
   - **Bot account:** Product menu > Integrations > Bot Accounts > Add Bot Account. If the option is missing, enable bot account creation under System Console > Integrations > Bot Accounts. Copy the token when it is shown; it is shown once.
   - **Regular account with a personal access token:** create a second user, enable personal access tokens under System Console > Integrations > Integration Management, then Profile > Security > Personal Access Tokens.
4. Add the bot account to the team (Invite People) and to any private channel it should read. Bots can read public channels they have joined.

## 3. Connect Streampack

Run `server-streampack` with the adapter enabled:

```dotenv
MATTERMOST_ENABLED=true
STREAMPACK_SECURITY_ENFORCE_EXTERNAL_SECRETS=false
```

Enforcement is off so the literal token from `connect` is accepted for the session. With it on, the next restart refuses to start until `MATTERMOST_LOCAL_TOKEN` is set (the value is never printed; you have it from the bot setup page), and then rewrites the stored token to `env://MATTERMOST_LOCAL_TOKEN`, exactly as production will.

From the console adapter or any super-admin identity:

```text
mattermost connect local http://localhost:8065 <token>
mattermost channels local
mattermost join local town-square
mattermost autoconnect local true
mattermost status
```

If Streampack itself runs inside `docker compose`, use `http://mattermost:8065` as the base URL instead of `localhost`.

## 4. Exercise it

In `town-square` on Mattermost:

```text
!version
@<bot-username> karma kotlin
```

Both are addressed commands; the reply appears in the channel. A direct message to the bot is addressed automatically. Plain chatter such as `kotlin++` is ambient and reaches operations that listen without being addressed.

For deployments, the same `connect` command with the production URL and token is all that changes, plus setting `MATTERMOST_<NAME>_TOKEN` in the environment. Outbound HTTPS from Streampack to the Mattermost server is the only network path required.

## Troubleshooting

- `Server 'local': not connected` after `connect`: check the token with `curl -H "Authorization: Bearer <token>" http://localhost:8065/api/v4/users/me`.
- Replies never arrive: the account must be a member of the channel, and the channel must not be muted (`mattermost unmute local town-square`).
- Messages are ignored: the signal character defaults to `!`; change it per server with `mattermost signal local ~` or globally with `MATTERMOST_SIGNAL`.
