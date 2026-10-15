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

Start with [What is Streampack?](explanation/what-is-streampack.md) if you are orienting yourself, [Deploy `server-streampack`](tutorials/deploy-server-streampack.md) if you are putting the bundled server on a machine, [Run the infobot on IRC](tutorials/run-infobot-on-irc.md) if you want the protocol path first, [Use GitHub repository watching](how-to/infobot/use-github-repository-watching.md) if you are setting up GitHub notifications, or [Use GitLab project watching](how-to/infobot/use-gitlab-project-watching.md) for GitLab.

To bring a server up from nothing with Docker, connected to Mattermost and GitLab, see [Deploy with Docker for Mattermost and GitLab](tutorials/deploy-with-docker-for-mattermost-and-gitlab.md). For the parts on their own: [Run the Published Image with Docker Compose](how-to/deploy/run-the-published-image.md), [Use the Console in Docker](how-to/deploy/use-the-console-in-docker.md), [Move Credentials into the Environment](how-to/deploy/move-credentials-into-the-environment.md), and, for why it works as it does, [How Credentials Reach the Environment](explanation/credentials-and-the-environment.md) and [The Local Admin Token](explanation/local-admin-token.md).

For Mattermost development against a private local server, see [Run a Local Mattermost for Development](how-to/develop/run-local-mattermost.md).

For repository-side release mechanics, see [Version and Publish Maven Artifacts to Nexus](how-to/develop/release-and-deploy-server-streampack.md).

To name a deployment, see [Name the Deployment](how-to/deploy/name-the-deployment.md). To add your own operations, a banner or settings without rebuilding the image, follow [Write and Load an Extension](tutorials/write-and-load-an-extension.md), then [Extend the Server with Jars](how-to/deploy/extend-the-server-with-jars.md); the directory is described in [The `ext/` Directory](reference/ext-directory.md), and what an extension is trusted with in [Extensions](explanation/extensions.md).

For AI prompt customization, see [Configure Generative Prompts](how-to/deploy/configure-generative-prompts.md).

For posts' recordings, the media directory they're stored in, and the podcast feed, see [Configure Media Storage and the Podcast Feed](how-to/deploy/configure-media-storage.md).

For what the message log keeps, what channel settings do, and why direct messages are kept but never read back, see [The Message Log](explanation/message-log.md).

To learn post search, follow [Search Posts with the API](tutorials/search-posts-with-the-api.md). For queries by task, see [Search Posts](how-to/blog/search-posts.md); for the grammar, [Post Search Syntax](reference/post-search-syntax.md); and for how it works, why `emergent` finds `emerging`, and what quotes change, [Post Search](explanation/post-search.md).

For how the Atlas (the map of the site's tags) is laid out, and why it holds still, see [The Atlas](explanation/atlas.md).

For how tag names are normalized, what system tags such as `_idea` are, and who sees them, see [Tag Names](reference/tags.md).

For blog/site HTTP integration, including one-time-code sign-in over email or Mattermost and the channel log endpoints, see [Blog HTTP API](reference/blog-http-api.md).

For the bundled bot command surface, use:

- [Use the Infobot](how-to/infobot/use-the-infobot.md) for the public user guide
- [Admin Text Operations](reference/admin-text-operations.md) for repository-facing operator commands
- [Command Parser](reference/command-parser.md) for parser internals and grammar/help rendering
