# Use the Console in Docker

The console reads commands from standard input and sends them as the super-admin. Under Docker Compose a service's standard input is closed unless the service asks for one, so the console needs two settings and a way to attach.

There are two other ways to give commands, which need neither: the [setup tool](https://github.com/streampack-dev/setup) opens the same commands over the web console API, and so does ui-pudl's admin console in a browser.

## Before You Start

- A super-admin. On a fresh database, set `ADMIN_EMAIL` in `.env`; startup creates the account. Without one the console logs `No super-admin account, so the console can't start` and ends.
- `CONSOLE_ENABLED=true` in `.env`.

## Give the Service a Terminal

In the compose file, on the `server-streampack` service:

```yaml
services:
  server-streampack:
    stdin_open: true
    tty: true
```

Recreate the container so the settings take effect:

```bash
docker compose up -d
```

Without them, the log says `The console's input is closed, so the console has ended`, and the server carries on without it.

## Attach, and Detach Again

```bash
docker attach "$(docker compose ps -q server-streampack)"
```

The console starts once the server is ready. Its `>` prompt may already have been printed before you attached, so press Enter to see a fresh one. Then type commands:

```text
> version
> secrets
```

To leave the console running and get your shell back, detach with **Ctrl-P Ctrl-Q**.

Don't press **Ctrl-C**: in an attached container it stops the server. `exit` or `quit` ends the console but not the server; the console comes back on the next restart.

## When Commands Store Credentials

Commands such as `mattermost connect` and `gitlab add` store credentials in the database until they're moved into the environment, and the next start fails until they are. See [Move Credentials into the Environment](move-credentials-into-the-environment.md).
