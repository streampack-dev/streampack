# Platform vs Bundled Server

Streampack has three layers:

| Layer | Examples | Responsibility |
|-------|----------|----------------|
| Extension API | `lib-extension-api` | Operation contracts and models, the command parser, channel state and the transformer chain as interfaces; no persistence |
| Platform core | `lib-core` | Event flow, persistence, users, and the implementations of the extension API |
| Capabilities | `operation-*`, `service-*`, feature libraries | Domain behavior and protocol adapters |
| Distribution | `server-streampack` | Runnable Spring Boot application that assembles capabilities |

`server-streampack` is a distribution. It is the server used by Bytecode.News, and it includes the infobot and site APIs. It is not the only possible Streampack application.

If you are deploying the included system, follow the `server-streampack` deployment docs. If you are extending Streampack, start with `lib-extension-api` and add operations in separate modules; reach for `lib-core` only for what the API doesn't offer yet.

## Why This Matters

Keeping the boundary clear affects documentation, testing, and packaging:

- Platform docs should describe operations, provenance, protocols, and service composition.
- Server docs should describe `.env`, Docker, PostgreSQL, mail, reverse proxies, and image publishing.
- Infobot docs should describe commands and protocol setup.
- Bytecode News docs should be treated as a deployment example, not the platform definition.
