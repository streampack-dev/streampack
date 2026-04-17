# What Is Streampack?

Streampack is an event-driven application platform. Its core idea is that protocols are interchangeable, but behavior should be shared.

An operation such as a factoid lookup, a karma update, a GitHub subscription command, or a blog content action should mean the same thing whether it comes from IRC, Discord, Slack, HTTP, MCP, or the console. Protocol adapters normalize input into messages with provenance, the operation pipeline handles behavior, and the egress side sends results back to the appropriate protocol.

## Platform Core

`lib-core` is the platform core. It provides:

- operation contracts such as `Operation`, `TypedOperation`, and `TranslatingOperation`
- `OperationService`, which executes operation chains
- `EventGateway` and Spring Integration channel contracts
- `Protocol` and `Provenance`, which identify where work came from and where responses should go
- user and service-binding models used across protocols
- common result types such as `OperationResult` and `OperationOutcome`

The important boundary is that `lib-core` is not the bundled application. It is the substrate for building Streampack systems.

## Bundled Server

`server-streampack` is the bundled runnable distribution in this repository. It composes `lib-core` with the included `operation-*`, `service-*`, and `lib-*` modules.

It currently includes:

- the Bytecode News-style blog/site API
- RSS/Atom feeds and sitemap support
- email OTP and OIDC authentication
- IRC, Discord, Slack, console, and bridge services
- factoids, karma, calculator, weather, dictionary, GitHub, RSS, MCP, and other included capabilities
- reusable generative infrastructure for AI-backed prompts and prompt overrides

That bundled server is production-usable, but it is also an example of composition. Streampack users can build their own server by selecting `lib-core`, selected libraries, and their own services and operations.

## Infobot

The infobot is a set of capabilities inside `server-streampack`. It is not the project identity. The current server has an infobot because Bytecode News uses that infrastructure, but Streampack itself is the underlying operation and integration platform.
