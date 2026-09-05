# lib-forge

Shared, forge-neutral code for watching code-hosting forges (GitHub today, GitLab next). It holds
what is identical between forges and leaves everything else to the forge module.

## What lives here

- `ForgeKind`: the forge and its noun for a pull/merge request (`PR`, `MR`).
- `client.ForgeClient`: the only forge-specific contract. REST reads (validate, issues since a
  cursor, change requests since a cursor, releases) and the webhook contract (envelope headers,
  supported events, project path in the payload, verification, event parsing).
- `model.ForgeEvent`: `IssueOpened`, `ChangeRequestOpened`, `ReleasePublished`, `Ping`. Polling
  and webhooks both produce these.
- `model.ForgeProject` / `model.ForgeSubscription`: the view of a module's entities the shared
  services need.
- `store.ForgeStore<P, S>`: the persistence port a module implements over its own tables.
- `format.ForgeEventFormatter`: the one place notification text is built.
- `service.AbstractForgeSubscriptionService`: add, subscribe, unsubscribe, remove, list.
- `service.AbstractForgePollingService`: tick-driven cursor polling and release diffing.
- `webhook.ForgeWebhookReceiver`: the controller body (headers, payload, lookup, verify, dedupe,
  parse, fan out). `ForgeWebhookFanOut`, `WebhookDeliveryTracker`, `SecretCipher` support it.

## What does not live here

Tables, migrations, properties, secret keys, command prefixes, and HTTP routes. Each forge module is
optional and owns those. A deployment may watch GitHub and GitLab with different credentials.

## Adding a forge

1. Entities implementing `ForgeProject` and `ForgeSubscription` over the module's own tables and
   Flyway migrations (versions are global across modules).
2. A `ForgeStore` implementation over the module's Spring Data repositories.
3. A `ForgeClient` implementation.
4. Thin `@Service` subclasses of the two service templates, a `ForgeWebhookFanOut` subclass, a
   `SecretCipher` subclass with the module's key, and a controller that builds a
   `ForgeWebhookReceiver` and owns the `/webhooks/<forge>` route.
5. Operations for the command prefix, delegating to the subscription service.

`service-github` is the reference implementation.
