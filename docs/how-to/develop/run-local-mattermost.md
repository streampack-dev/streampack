# Run a Local Mattermost for Development

`service-mattermost` connects to a Mattermost server as an ordinary account: it reads posts over the server's WebSocket API and replies over REST. The production server you care about may sit on a restricted network, so this page brings up a private Mattermost on your machine and exercises command ingestion and replies end to end against it. Nothing here assumes access to any remote instance.

## 1. Start Mattermost

The repository's `docker-compose.yml` carries a `mattermost` profile using the official preview image, which bundles its own database:

```bash
docker compose --profile mattermost up -d mattermost
```

Open <http://localhost:8065>. The preview image is for evaluation only: it uses known passwords, keeps no durable data across image upgrades, and must not be used in production.

**Apple Silicon:** Mattermost publishes amd64 images only (the preview, team, and enterprise images alike), so the compose service is pinned to `platform: linux/amd64` and runs under emulation. Turn on **Docker Desktop > Settings > General > Use Rosetta for x86_64/amd64 emulation on Apple Silicon**; without it Docker falls back to QEMU, which works but is markedly slower to start. First start takes a minute or two either way; `curl http://localhost:8065/api/v4/system/ping` returns `{"status":"OK"}` when it is ready.

## 2. Create the admin, a team, and the bot account

You do not need to learn the Mattermost admin UI. The preview image ships `mmctl`, Mattermost's admin CLI, and this sequence does everything from the terminal. It was run verbatim against the compose service; every command prints a one-line confirmation.

First create the admin account. The first account on a fresh server is made a system admin automatically:

```bash
curl -s -X POST http://localhost:8065/api/v4/users \
  -H 'Content-Type: application/json' \
  -d '{"email":"admin@example.com","username":"admin","password":"Password1!"}'
```

Then log `mmctl` in as that admin and create the team, enable bot accounts (off by default), create the bot, generate its token, and put the bot in the team and in `town-square`:

```bash
mm() { docker compose --profile mattermost exec mattermost /mm/mattermost/bin/mmctl "$@"; }

mm auth login http://localhost:8065 --name local --username admin --password 'Password1!'
mm team create --name dev --display-name "Dev"
mm team users add dev admin
mm config set ServiceSettings.EnableBotAccountCreation true
mm bot create nevet --display-name "Nevet"
mm token generate nevet streampack        # prints "<token>: streampack"; the token is shown once
mm team users add dev nevet
mm channel users add dev:town-square nevet
```

The token is the 26-character value before `: streampack` in the `token generate` output. Confirm it works and belongs to the bot:

```bash
curl -s -H "Authorization: Bearer <token>" http://localhost:8065/api/v4/users/me
```

The response names `"username":"nevet"` with `"is_bot":true`. To add the bot to a private channel later, `mm channel users add dev:<channel> nevet` does it; the bot cannot join private channels on its own.

If you prefer the UI: sign up at <http://localhost:8065>, create a team, enable **System Console > Integrations > Bot Accounts**, create the bot under **Integrations > Bot Accounts**, copy its token from the confirmation page, and invite it to the team and channel.

## 3. Connect Streampack

Run `server-streampack` outside compose with the console adapter, so you have a place to type admin commands. The console reads standard input, which a compose service has no way to provide. The compose `db` service supplies Postgres:

```bash
docker compose up -d db
just build
CONSOLE_ENABLED=true MATTERMOST_ENABLED=true STREAMPACK_SECURITY_ENFORCE_EXTERNAL_SECRETS=false \
  java -jar server-streampack/target/server-streampack-*-exec.jar
```

Enforcement is off so the literal token from `connect` is accepted for the session. With it on, the next restart refuses to start until `MATTERMOST_LOCAL_TOKEN` is set (the value is never printed; you have it from the `token generate` output), and then rewrites the stored token to `env://MATTERMOST_LOCAL_TOKEN`, exactly as production will.

At the console prompt:

```text
mattermost connect local http://localhost:8065 <token>
mattermost channels local
mattermost join local town-square
mattermost autoconnect local true
mattermost status
```

`status` reports `Server 'local': connected` once the socket has authenticated. If Streampack itself ever runs inside `docker compose`, use `http://mattermost:8065` as the base URL instead of `localhost`.

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
