# Streampack Documentation

Streampack documentation is organized with Diataxis:

| Section | Purpose |
|---------|---------|
| [Tutorials](tutorials/) | Guided learning paths for deploying the bundled server and writing extensions |
| [How-to](how-to/) | Task-oriented operational and development procedures |
| [Reference](reference/) | Factual module, configuration, and API details |
| [Explanation](explanation/) | Design rationale and conceptual model |

Two concepts are kept separate throughout the docs:

- `lib-core` is the Streampack platform core: operations, provenance, protocols, user identity, and the event pipeline.
- `server-streampack` is the bundled runnable server distribution used by Bytecode News. It includes the infobot, blog/site APIs, feeds, authentication, MCP, and integrations.

Start with [What is Streampack?](explanation/what-is-streampack.md) if you are orienting yourself, or [Deploy `server-streampack`](tutorials/deploy-server-streampack.md) if you are putting the bundled server on a machine.
