# Use GitHub Repository Watching

`service-github` lets the bundled infobot watch GitHub repositories and deliver issue, pull request, release, and webhook notifications into subscribed destinations. GitLab projects work the same way through [GitLab project watching](use-gitlab-project-watching.md).

## Register a Repository

For a public repository:

```text
github add owner/repo
```

For a repository that needs authenticated GitHub API access:

```text
github add owner/repo <token>
github add owner/repo env://GITHUB_OWNER_REPO_TOKEN
```

This registers the repository and seeds current issues, pull requests, and releases so later notifications are incremental.

Tokens are not kept in the database as plain text for long. A literal token is accepted, used to seed the repository, and the response names the environment variable that will hold it, in the form `GITHUB_<OWNER>_<REPO>_TOKEN` (non-alphanumeric characters become `_`). Set that variable before the next restart. On startup, with `STREAMPACK_SECURITY_ENFORCE_EXTERNAL_SECRETS` on, the server refuses to start while any active repository still holds a literal whose variable is not set, naming the variables to set; it never prints the token values. Once a variable is present the stored literal is rewritten to an `env://` reference. Passing `env://KEY` directly skips the literal phase; the variable must already be set.

## Subscribe a Destination

From the current chat destination:

```text
github subscribe owner/repo
```

From a console or admin context with an explicit target:

```text
github subscribe owner/repo to irc://libera/%23java
```

Check active subscriptions:

```text
github subscriptions
github subscriptions for irc://libera/%23java
```

## Get Pipeline Outcomes

Pipeline notifications are opt-in per subscription. Add filters after the repository when subscribing:

```text
github subscribe owner/repo pipelines
github subscribe owner/repo pipelines:failed
github subscribe owner/repo pipelines:default-branch
github subscribe owner/repo pipelines:branch:develop:failed
github subscribe owner/repo pipelines:failed pipelines:branch:develop on ghe.example.com to irc://libera/%23dev
```

- `pipelines` reports every workflow run that belongs to a pull request; `pipelines:failed` only the ones that need attention.
- `pipelines:default-branch` reports runs on whatever the repository's default branch is.
- `pipelines:branch:<name>` reports runs on one named branch, for repositories whose working branch is not the default.
- Any selector takes a trailing `:failed`. Several filters may be combined. Issues, pull requests, and releases are always included.

Re-subscribing with filters replaces the previous filters; to drop them, unsubscribe and subscribe again. `github subscriptions` shows each subscription's filters in brackets.

A notification is sent once, when a run leaves the in-progress state, and again only when a re-run settles. GitHub starts one workflow run per workflow, so each run is reported separately with its workflow name. Failed runs name the jobs that failed:

```text
[owner/repo] PR #12 workflow 'CI' FAILED (unit-tests, lint) - https://github.com/owner/repo/actions/runs/123
[owner/repo] PR #13 workflow 'CI' succeeded (4m12s) - https://github.com/owner/repo/actions/runs/124
[owner/repo] main workflow 'Deploy' CANCELED - https://github.com/owner/repo/actions/runs/125
```

Polling and webhooks report the same run once between them. To receive run outcomes by webhook, add `workflow_run` to the events the GitHub hook sends; failed runs cost one extra API call to list their jobs. Runs for pull requests from forks are not associated with the pull request by GitHub and are not reported.

## Enable Webhooks

For normal validated webhook setup:

```text
github webhook owner/repo
```

For operator-managed private repository setup:

```text
github webhook private owner/repo
```

Private mode skips remote validation and baseline seeding, creates or reuses the local repository record, and emits the webhook secret anyway.

After either command, configure GitHub to deliver webhooks (issues, pull requests, releases, and workflow runs if you want pipeline outcomes) to:

```text
<base-url>/webhooks/github
```

using the one-time secret emitted by Streampack.

## Watch a GitHub Enterprise Server

Every repository belongs to a GitHub instance. `github.com` is registered by default and is what every command uses when you omit `on <host>`. To watch repositories on GitHub Enterprise Server (or another GitHub-compatible forge), register the instance once:

```text
github instance add https://ghe.example.com
github instance add https://ghe.example.com <token>
github instance add https://ghe.example.com env://GITHUB_INSTANCE_GHE_EXAMPLE_COM_TOKEN
github instance list
```

A base URL gets Enterprise Server's `/api/v3` appended; give the full API URL instead if your installation serves the API elsewhere. The optional token is the instance default: repositories on that instance use it unless they were added with a token of their own. Literal tokens are externalized on the next restart exactly like repository tokens, to `GITHUB_INSTANCE_<HOST>_TOKEN`.

Then name the instance with `on <host>` on any command that takes a repository. The shared `on <host>` grammar is described in [Admin Text Operations](../../reference/admin-text-operations.md#forge-instances-and-on-host).

```text
github add owner/repo on ghe.example.com
github add owner/repo <token> on ghe.example.com
github subscribe owner/repo on ghe.example.com
github subscribe owner/repo on ghe.example.com to irc://libera/%23java
github unsubscribe owner/repo on ghe.example.com
github webhook owner/repo on ghe.example.com
github remove owner/repo on ghe.example.com
```

The same `owner/repo` may be watched on github.com and on an Enterprise Server at once. Notifications from github.com read `[owner/repo]`; notifications from any other instance read `[ghe.example.com owner/repo]` so one channel can tell them apart. Repository tokens on other instances are externalized to `GITHUB_<HOST>_<OWNER>_<REPO>_TOKEN`.

Webhooks for an Enterprise Server repository are delivered to that instance's own route, which `github webhook ... on <host>` prints:

```text
<base-url>/webhooks/github/<instance-id>
```

The bare `<base-url>/webhooks/github` route keeps serving github.com, so hooks configured before instances existed continue to verify unchanged.

## Stop Watching

Unsubscribe the current destination:

```text
github unsubscribe owner/repo
```

Remove the repository from active watching:

```text
github remove owner/repo
```
