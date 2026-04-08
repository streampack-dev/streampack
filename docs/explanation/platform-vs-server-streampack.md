# Platform vs Bundled Server

Streampack has three layers:

| Layer | Examples | Responsibility |
|-------|----------|----------------|
| Platform core | `lib-core` | Operation contracts, event flow, provenance, users |
| Capabilities | `operation-*`, `service-*`, feature libraries | Domain behavior and protocol adapters |
| Distribution | `server-streampack` | Runnable Spring Boot application that assembles capabilities |

`server-streampack` is a distribution. It is the server used by Bytecode.News, and it includes the infobot and site APIs. It is not the only possible Streampack application.

If you are deploying the included system, follow the `server-streampack` deployment docs. If you are extending Streampack, start with `lib-core` and add operations or services in separate modules.

## Why This Matters

Keeping the boundary clear affects documentation, testing, and packaging:

- Platform docs should describe operations, provenance, protocols, and service composition.
- Server docs should describe `.env`, Docker, PostgreSQL, mail, reverse proxies, and image publishing.
- Infobot docs should describe commands and protocol setup.
- Bytecode News docs should be treated as a deployment example, not the platform definition.
