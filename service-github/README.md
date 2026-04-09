# service-github

`service-github` provides GitHub repository watching, subscription management, polling, and webhook ingestion.

## User Commands

These commands are normally addressed through a protocol adapter, for example `!github ...`. Administrative commands require an admin-capable identity.

### Register a repository

```text
github add owner/repo
github add owner/repo <token>
```

Registers a repository for watching.

- Use the plain form for public repositories.
- Use the token form when the GitHub API requires authenticated access.
- The token argument is redacted from command logging.

### List repositories

```text
github list
```

Shows all registered repositories.

Markers:

- `[inactive]` means the repository has been removed from active watching.
- `[webhook]` means the repository is configured for webhook delivery.

### Subscribe and unsubscribe

```text
github subscribe owner/repo
github unsubscribe owner/repo
github subscriptions
```

These act on the current destination.

Explicit-target forms are also supported:

```text
github subscribe owner/repo to <destination-uri>
github unsubscribe owner/repo from <destination-uri>
github subscriptions for <destination-uri>
```

### Remove a repository

```text
github remove owner/repo
```

Marks the repository inactive and deactivates active subscriptions.

### Enable webhook delivery

```text
github webhook owner/repo
github webhook private owner/repo
```

Normal webhook mode:

- validates the repository remotely
- seeds baseline issues, pull requests, and releases
- is appropriate for public repositories or repositories already registered with a token

Private webhook mode:

- skips remote validation and baseline seeding
- creates or reuses the local repository record
- is intended for operator-managed private repository setups where Streampack cannot validate the repository directly

After enabling webhook mode, configure GitHub to POST to:

```text
<base-url>/webhooks/github
```

Use the one-time secret emitted by Streampack as the webhook secret in GitHub.

## Example Flows

### Public repository polling setup

```text
github add openjdk/jdk
github subscribe openjdk/jdk
github subscriptions
```

### Authenticated private repository registration

```text
github add my-org/private-repo ghp_xxxxx
github subscribe my-org/private-repo
```

### Private repository webhook-only setup

```text
github webhook private my-org/private-repo
```

This path trusts operator intent and waits for signed webhook deliveries to prove the configuration.

### Switch an existing repository to webhook mode

```text
github add openjdk/jdk
github webhook openjdk/jdk
github list
```
