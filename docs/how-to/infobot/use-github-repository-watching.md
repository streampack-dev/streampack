# Use GitHub Repository Watching

`service-github` lets the bundled infobot watch GitHub repositories and deliver issue, pull request, release, and webhook notifications into subscribed destinations.

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

Tokens are not kept in the database as plain text for long. A literal token is accepted, used to seed the repository, and the response names the environment variable that will hold it, in the form `GITHUB_<OWNER>_<REPO>_TOKEN` (non-alphanumeric characters become `_`). Set that variable before the next restart: on startup, literal tokens are rewritten to `env://` references and, with `STREAMPACK_SECURITY_ENFORCE_EXTERNAL_SECRETS` on, the server refuses to start until every active repository's variable is present, printing the `export` lines to add. Passing `env://KEY` directly skips the literal phase; the variable must already be set.

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

After either command, configure GitHub to deliver webhooks to:

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
