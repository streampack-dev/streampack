# Move Credentials into the Environment

A credential given at the console (a Mattermost token in `mattermost connect`, a GitLab token in `gitlab add`, an IRC SASL password, Slack tokens) is stored in the database as typed, and works at once. With `STREAMPACK_SECURITY_ENFORCE_EXTERNAL_SECRETS` on, as it is by default, the next start refuses to run until each one has an environment variable of its own. Once the variable is set, startup replaces the stored value with an `env://` reference to it. [How Credentials Reach the Environment](../../explanation/credentials-and-the-environment.md) explains why.

## 1. See Which Variables Are Needed

At the console:

```text
> secrets
2 environment variables to set before the next start:
GITLAB_GROUP_PROJECT_TOKEN: GitLab project 'group/project' token, stored in the database until it's set
MATTERMOST_WORK_TOKEN: Mattermost server 'work' token, stored in the database until it's set
```

Or over HTTP, as a super-admin:

```bash
curl -fsS -H "Authorization: Bearer $TOKEN" https://api.example.com/admin/secrets
```

Either way you get names, never values. "Not set" means the database already refers to the variable, but the environment doesn't have it.

## 2. Add Them to `.env`

Use the same values you gave at the console. If you no longer have one, issue a new token and use that instead:

```dotenv
MATTERMOST_WORK_TOKEN=...
GITLAB_GROUP_PROJECT_TOKEN=...
```

Under the repository's compose file, every `.env` entry reaches the container (`env_file: .env`). For a compose file that lists variables one by one, add each variable to the service's `environment:` as well.

## 3. Recreate the Container

```bash
docker compose up -d
```

`docker compose restart` doesn't re-read `.env`, so the container would start with its old environment and fail the check again. `up -d` recreates it.

## 4. Check

The log says `Externalized literal credential to MATTERMOST_WORK_TOKEN` for each one, and `secrets` says:

```text
No environment variables are needed before the next start.
```

If the start fails instead, the log's `SECURITY STARTUP CHECK FAILED` block names the variables still missing.
