# Deploy with Docker for Mattermost and GitLab

In this tutorial you'll bring up `server-streampack` on a fresh Linux server with Docker, connect it to a Mattermost server, and have it post a GitLab project's merge requests into a Mattermost channel. You'll use [`streampack-setup`](https://github.com/streampack-dev/setup), which writes the configuration, starts the server, and then gives you its console.

It takes about half an hour, most of it spent gathering tokens.

## What You Need

**A Linux server** (amd64 or arm64) with Docker and the Compose plugin, and a user who can run `docker` without `sudo`:

```bash
docker compose version
```

**From Mattermost:**

- The server's address, reachable from your Linux server (`https://mattermost.example.com`).
- A token for Streampack to act as. The best choice is a bot account: a Mattermost admin turns on **System Console > Integrations > Bot Accounts**, creates the bot, and gives you its token. A personal access token works too.
- A team and a channel, with that account added to both.

**From GitLab:**

- The instance's address (`https://gitlab.example.com`), or gitlab.com.
- A token with the `read_api` scope, from a project access token (Reporter is enough) or a personal one.
- The path of a project to watch (`group/project`).

**An email address** for Streampack's super-admin: you.

## 1. Install the Setup Tool

```bash
curl -fLO https://github.com/streampack-dev/setup/releases/latest/download/streampack-setup-linux-amd64
install -m 755 streampack-setup-linux-amd64 ~/streampack-setup
```

Use `linux-arm64` on an ARM server. The tool's [README](https://github.com/streampack-dev/setup#install) shows how to check the download against its checksums.

## 2. Answer the First Questions

Make a directory for the deployment, and run the tool in it:

```bash
mkdir ~/streampack && cd ~/streampack
~/streampack-setup
```

It asks what starting the server needs. Press Enter to take a default in brackets:

```text
Server version [1.16.0]:
The super-admin's email address: you@example.com
The API's public address [http://localhost:8080]: https://api.example.com
The site's public address [http://localhost:3000]: https://www.example.com
Turn on Mattermost? [Y/n]:
Turn on GitLab? [Y/n]:
Turn on IRC? [y/N]:
Turn on Slack? [y/N]:
Turn on Discord? [y/N]:
Send mail (sign-in codes) through an SMTP server? [y/N]:
Wrote compose.yaml
Wrote .env (readable by you alone)
```

You didn't give it any Mattermost or GitLab tokens. Those come later, at the console. The tool generated the database password, the JWT secret and the webhook keys itself; they're in `.env`.

## 3. Watch It Start

The tool carries straight on: it pulls the images, starts the server and PostgreSQL, and waits for the server to answer:

```text
$ docker compose pull
$ docker compose up -d
Waiting for the server to answer at http://127.0.0.1:8080/features
The server is ready.
Console open as you.
```

On this first start the server created your super-admin account from the email address. The tool signs in as it with a short-lived token that the server writes inside its own container. [The Local Admin Token](../explanation/local-admin-token.md) explains why that's safe.

## 4. Connect Mattermost

At the `>` prompt you're typing the server's own commands. Register the Mattermost server, naming it `work`:

```text
> mattermost connect work https://mattermost.example.com <the bot's token>
Connecting to 'work'...
> mattermost status work
Server 'work': connected
```

Have it reconnect whenever the server starts:

```text
> mattermost autoconnect work true
```

Find the channel to post into. `mattermost channels` lists the channels the account can see, with each one's id in brackets; a word narrows the list:

```text
> mattermost channels work builds
  Builds [8xk3m1q5nfdq7c9w4yzr2t6ape] on engineering
```

Keep that id for step 6.

## 5. Watch a GitLab Project

Register your GitLab instance with its token, then the project. On gitlab.com, skip the first line and give the token to `gitlab add` instead:

```text
> gitlab instance add https://gitlab.example.com <the read_api token>
> gitlab add group/project on gitlab.example.com
```

`gitlab add` looks the project up and notes its current issues and merge requests, so you'll hear about new ones from now on.

## 6. Send Its News to the Channel

Subscribe the channel, using the server's name and the channel's id from step 4:

```text
> gitlab subscribe group/project on gitlab.example.com to mattermost://work/8xk3m1q5nfdq7c9w4yzr2t6ape
```

When someone opens a merge request in the project, it appears in the channel. GitLab is polled every hour by default (`GITLAB_POLL_INTERVAL`). If GitLab can reach your server over HTTPS, a webhook is quicker: see [Enable Webhooks](../how-to/infobot/use-gitlab-project-watching.md#enable-webhooks).

## 7. Move the Tokens into `.env`

The two tokens you typed are in the server's database for now. Streampack keeps credentials in the environment instead, and its next start refuses to run until they're there. Ask which variables it needs:

```text
> secrets
2 environment variables to set before the next start:
GITLAB_INSTANCE_GITLAB_EXAMPLE_COM_TOKEN: GitLab instance 'gitlab.example.com' default token, stored in the database until it's set
MATTERMOST_WORK_TOKEN: Mattermost server 'work' token, stored in the database until it's set
```

Leave the console with `:quit`. The tool offers to move them:

```text
> :quit
2 credentials given at the console still need a place in .env; the next start fails without them. Move them now? [Y/n]:
GITLAB_INSTANCE_GITLAB_EXAMPLE_COM_TOKEN (GitLab instance 'gitlab.example.com' default token), blank to skip:
MATTERMOST_WORK_TOKEN (Mattermost server 'work' token), blank to skip:
Wrote them into .env.
$ docker compose up -d
The server is ready.
Done: every credential is in .env.
```

Paste each token again when asked; nothing you type is shown. The tool wrote them into `.env` and recreated the server, which replaced the stored tokens with references to the variables. [How Credentials Reach the Environment](../explanation/credentials-and-the-environment.md) explains why it's done in two steps.

## 8. Come Back Later

Run the tool again in the same directory whenever you want the console:

```bash
cd ~/streampack && ~/streampack-setup
```

It sees the `.env`, skips the questions, makes sure the server is up, and opens the console.

## What You Have Now

- `server-streampack` and PostgreSQL, running under Docker Compose and restarting with the box. The configuration is in `~/streampack/.env` and `compose.yaml`.
- A Mattermost connection that reconnects by itself, and a GitLab project whose news reaches a channel.
- The API on `127.0.0.1:8080`, for a reverse proxy to put on the public address: see [Configure a Reverse Proxy](../how-to/deploy/configure-reverse-proxy.md).

## Next

- [Use GitLab Project Watching](../how-to/infobot/use-gitlab-project-watching.md): filters, pipelines, webhooks.
- [Run the Published Image with Docker Compose](../how-to/deploy/run-the-published-image.md): the same files, without the tool, and upgrading.
- [Back Up and Restore PostgreSQL](../how-to/deploy/backup-and-restore-postgres.md).
- [Admin Text Operations](../reference/admin-text-operations.md): every console command.
