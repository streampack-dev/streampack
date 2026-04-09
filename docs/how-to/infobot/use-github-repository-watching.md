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
```

This registers the repository and seeds current issues, pull requests, and releases so later notifications are incremental.

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

## Stop Watching

Unsubscribe the current destination:

```text
github unsubscribe owner/repo
```

Remove the repository from active watching:

```text
github remove owner/repo
```
