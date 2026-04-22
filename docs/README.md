# Streampack Documentation

Streampack documentation is organized with Diataxis:

| Section | Purpose |
|---------|---------|
| [Tutorials](tutorials/) | Guided learning paths for deploying the bundled server, running the infobot, and writing extensions |
| [How-to](how-to/) | Task-oriented operational and development procedures |
| [Reference](reference/) | Factual module, configuration, and API details |
| [Explanation](explanation/) | Design rationale and conceptual model |

Two concepts are kept separate throughout the docs:

- `lib-core` is the Streampack platform core: operations, provenance, protocols, user identity, and the event pipeline.
- `server-streampack` is the bundled runnable server distribution used by Bytecode News. It includes the infobot, blog/site APIs, feeds, authentication, MCP, and integrations.

Start with [What is Streampack?](explanation/what-is-streampack.md) if you are orienting yourself, [Deploy `server-streampack`](tutorials/deploy-server-streampack.md) if you are putting the bundled server on a machine, [Run the infobot on IRC](tutorials/run-infobot-on-irc.md) if you want the protocol path first, or [Use GitHub repository watching](how-to/infobot/use-github-repository-watching.md) if you are setting up GitHub notifications.

For AI prompt customization, see [Configure Generative Prompts](how-to/deploy/configure-generative-prompts.md).

For blog/site HTTP integration, see [Blog HTTP API](reference/blog-http-api.md).

For the bundled bot command surface, use:

- [Use the Infobot](how-to/infobot/use-the-infobot.md) for the public user guide
- [Admin Text Operations](reference/admin-text-operations.md) for repository-facing operator commands
- [Command Parser](reference/command-parser.md) for parser internals and grammar/help rendering
