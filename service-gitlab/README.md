# service-gitlab

`service-gitlab` provides GitLab project watching, subscription management, polling, and webhook ingestion for gitlab.com and self-hosted GitLab. It is optional: nothing in the module is active unless `streampack.gitlab.enabled=true` (`GITLAB_ENABLED`).

It is built on `lib-forge`, the same shared machinery as `service-github`, so the command surface and notification format are the same with `gitlab` as the prefix and "MR" for merge requests.

## User Commands

Administrative commands require an admin-capable identity.

### Register a GitLab instance

```text
gitlab instance add <url>
gitlab instance add <url> <token>
gitlab instance list
```

`gitlab.com` is registered automatically. Register a self-hosted GitLab by its base URL; `/api/v4` is appended unless you give the full API URL. The optional token is the default credential for projects on that instance.

### Register a project

```text
gitlab add group/project
gitlab add group/subgroup/project <token>
gitlab add group/project on <host>
gitlab add group/project <token> on <host>
```

Registers a project by its full path and records its numeric GitLab id. Use `on <host>` for a registered instance other than gitlab.com; every other command that names a project accepts the same suffix. The token argument is redacted from command logging.

### List, subscribe, unsubscribe, remove

```text
gitlab list
gitlab subscribe group/project [on <host>] [to <destination-uri>]
gitlab unsubscribe group/project [on <host>] [from <destination-uri>]
gitlab subscriptions [for <destination-uri>]
gitlab remove group/project [on <host>]
```

### Pipeline outcomes

```text
gitlab subscribe group/project pipelines
gitlab subscribe group/project pipelines:failed
gitlab subscribe group/project pipelines:default-branch
gitlab subscribe group/project pipelines:branch:<name>[:failed]
```

Filters opt a subscription into pipeline outcomes on top of the always-on issues, merge requests, and releases. A pipeline is reported once when it settles and again only when a retry settles; failures name the failed jobs. Polling and webhooks share one record of what has been reported, so a settlement is never sent twice.

### Enable webhook delivery

```text
gitlab webhook group/project [on <host>]
gitlab webhook private group/project [on <host>]
```

The command prints the URL to configure on GitLab and sends the secret token directly to the requesting user. Configure Issues, Merge request, and Releases events. Private mode skips the API lookup, so the project is recorded without its numeric id and deliveries match it by path.

```text
<base-url>/webhooks/gitlab
<base-url>/webhooks/gitlab/<instance-id>
```

The bare route serves gitlab.com; every other instance has its own route.

## Webhook Contract

- `X-Gitlab-Token` is compared in constant time with the stored secret token. GitLab does not sign payloads, so the route must be reached over HTTPS.
- `X-Gitlab-Event` selects the hook: `Issue Hook`, `Merge Request Hook`, `Release Hook`. Others are accepted and ignored.
- `X-Gitlab-Event-UUID` deduplicates redeliveries within `streampack.gitlab.delivery-dedupe-ttl`.
- Only `open` issue and merge request actions and `create` release actions are reported.
- The project is matched by `project.id` first and `project.path_with_namespace` second, always within the instance the route names.

## Configuration

| Property | Environment variable | Default |
|----------|----------------------|---------|
| `streampack.gitlab.enabled` | `GITLAB_ENABLED` | `false` |
| `streampack.gitlab.poll-interval` | `GITLAB_POLL_INTERVAL` | `PT60M` |
| `streampack.gitlab.webhook-secret-key` | `GITLAB_WEBHOOK_SECRET_KEY` | unset (webhooks refused) |
| `streampack.gitlab.webhook-base-url` | `GITLAB_WEBHOOK_BASE_URL` | falls back to `BASE_URL` |
| `streampack.gitlab.delivery-dedupe-ttl` | `GITLAB_WEBHOOK_DEDUPE_TTL` | `PT6H` |

Project and instance tokens are externalized to `GITLAB_<PATH>_TOKEN`, `GITLAB_<HOST>_<PATH>_TOKEN`, and `GITLAB_INSTANCE_<HOST>_TOKEN` as described in [Environment Variables](../docs/reference/environment-variables.md).
