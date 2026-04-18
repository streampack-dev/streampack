# Infobot Commands

This page is now the index for the bundled infobot command surface.

For the public, user-facing bot guide, start with:

- [Use the Infobot](../how-to/infobot/use-the-infobot.md)

For repository-facing operator and moderator commands, see:

- [Admin Text Operations](admin-text-operations.md)

## Quick Categories

| Area | Examples |
|------|----------|
| Knowledge and lookup | `spring`, `define ubiquitous`, `rfc 2616`, `calc 2+3`, `weather Boston` |
| Ambient behavior | `kotlin++`, paste a URL |
| Messaging and channel tools | `tell alice hello`, `bridge provenance` |
| Games | `hangman`, `21`, `safecracker` |
| AI and creative tools | `ask ...`, `poem ...`, `be alice` |
| Idea capture | `article ...`, `content ...`, `done` |

## Protocol Addressing

Most commands are addressed through the protocol adapter, such as `!calc 2+3` or a bot mention.
Some operations are ambient and react to normal conversation instead.

## Module References

For module-local command details and implementation notes, see:

- [lib-core/README.md](../../lib-core/README.md)
- [operation-factoid/README.md](../../operation-factoid/README.md)
- [service-github/README.md](../../service-github/README.md)
- [service-rss/README.md](../../service-rss/README.md)
- [service-irc/README.md](../../service-irc/README.md)
- [service-slack/README.md](../../service-slack/README.md)
- [service-bridge/README.md](../../service-bridge/README.md)
