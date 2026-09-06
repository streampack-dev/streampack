# Use GitLab Project Watching

`service-gitlab` lets the bundled infobot watch GitLab projects on gitlab.com or a self-hosted GitLab and deliver issue, merge request, release, and webhook notifications into subscribed destinations. It mirrors [GitHub repository watching](use-github-repository-watching.md) with `gitlab` as the command prefix and "MR" as the change-request noun.

## Enable the Module

GitLab watching is off by default. Set `GITLAB_ENABLED=true` and, if you will use webhooks, `GITLAB_WEBHOOK_SECRET_KEY` (see [Environment Variables](../../reference/environment-variables.md)). When enabled, `/features` lists `gitlab` among the operation groups.

## Register a Project

Projects are named by their full path, including subgroups:

```text
gitlab add group/project
gitlab add group/subgroup/project
```

For a project that needs authenticated API access:

```text
gitlab add group/project <token>
gitlab add group/project env://GITLAB_GROUP_PROJECT_TOKEN
```

This looks the project up, records its numeric GitLab id, and seeds current issues, merge requests, and releases so later notifications are incremental.

Tokens follow the same externalization rule as GitHub tokens: a literal token is stored, the response names the environment variable that will hold it (`GITLAB_<PATH>_TOKEN`, with `/` and other non-alphanumerics becoming `_`, e.g. `GITLAB_GROUP_SUBGROUP_PROJECT_TOKEN`), and on the next restart the literal is replaced by an `env://` reference. With `STREAMPACK_SECURITY_ENFORCE_EXTERNAL_SECRETS` on, startup fails until every active project's variable is present.

## Subscribe a Destination

```text
gitlab subscribe group/project
gitlab subscribe group/project to irc://libera/%23java
gitlab subscriptions
gitlab subscriptions for irc://libera/%23java
```

## Enable Webhooks

```text
gitlab webhook group/project
gitlab webhook private group/project
```

Normal mode validates the project and seeds its baseline before switching to webhook delivery. Private mode skips the API entirely: the project is recorded by path only, without its numeric id, so deliveries for it match on the path.

The command prints the URL to configure and sends the secret token to you directly. On GitLab, open the project's **Settings > Webhooks**, enter the URL and the secret token, and enable the **Issues events**, **Merge request events**, and **Releases events** triggers:

```text
<base-url>/webhooks/gitlab
```

GitLab does not sign webhook payloads. It sends the secret token verbatim in the `X-Gitlab-Token` header, and Streampack compares it in constant time with the stored token. That means the webhook URL must be HTTPS all the way to Streampack, or a proxy that terminates TLS in front of it; a plain HTTP hop exposes the token.

Deliveries are matched to a project by the numeric id in the payload first, so a project that is renamed or moved keeps routing to its subscribers. Redeliveries are recognized by `X-Gitlab-Event-UUID` and ignored.

## Watch a Self-Hosted GitLab

Every project belongs to a GitLab instance. `gitlab.com` is registered by default and is used when you omit `on <host>`. Register a self-hosted server once:

```text
gitlab instance add https://gitlab.example.com
gitlab instance add https://gitlab.example.com <token>
gitlab instance add https://gitlab.example.com env://GITLAB_INSTANCE_GITLAB_EXAMPLE_COM_TOKEN
gitlab instance list
```

A base URL gets `/api/v4` appended; give the full API URL if yours lives elsewhere. The optional token is the instance default, used by every project on it that was not added with a token of its own, and is externalized to `GITLAB_INSTANCE_<HOST>_TOKEN`.

Then use `on <host>` with any command that names a project. The grammar is shared with GitHub and described once in [Admin Text Operations](../../reference/admin-text-operations.md#forge-instances-and-on-host).

```text
gitlab add group/project on gitlab.example.com
gitlab subscribe group/project on gitlab.example.com
gitlab webhook group/project on gitlab.example.com
gitlab remove group/project on gitlab.example.com
```

Notifications from gitlab.com read `[group/project]`; from any other instance they read `[gitlab.example.com group/project]`. Project tokens off gitlab.com are externalized to `GITLAB_<HOST>_<PATH>_TOKEN`. Webhooks for a self-hosted project go to that instance's own route, which the `gitlab webhook ... on <host>` command prints:

```text
<base-url>/webhooks/gitlab/<instance-id>
```

## Stop Watching

```text
gitlab unsubscribe group/project
gitlab remove group/project
```
