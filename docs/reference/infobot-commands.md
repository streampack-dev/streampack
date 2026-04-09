# Infobot Commands

This reference is intentionally high-level while command docs are being reorganized from the old documentation set.

The bundled infobot currently includes capabilities from modules such as:

| Capability | Examples |
|------------|----------|
| Calculator | `calc 2+3` |
| Factoids | `spring`, `spring is A Java framework`, `spring.tags=java,framework` |
| Karma | `kotlin++`, `xml--` |
| Specs | `rfc 2616`, `jep 456`, `jsr 330`, `pep 8` |
| Dictionary | `define ubiquitous` |
| GitHub/RSS | `github add openjdk/jdk`, `github subscribe openjdk/jdk`, feed subscription commands |
| URL titles | paste a URL and receive a title |

Most commands require addressing through the protocol adapter, such as `!calc 2+3` or a bot mention. Some operations are unaddressed and watch ambient conversation.

## GitHub

GitHub repository watching is provided by `service-github`.

Common commands:

```text
github add owner/repo
github add owner/repo <token>
github list
github subscribe owner/repo
github unsubscribe owner/repo
github subscriptions
github remove owner/repo
github webhook owner/repo
github webhook private owner/repo
```

Notes:

- `github add owner/repo <token>` is for repositories that need authenticated API access.
- `github webhook owner/repo` validates the repository and switches it to webhook delivery.
- `github webhook private owner/repo` skips remote validation and is intended for operator-managed private repository webhook setups.

For fuller details and example flows, see [service-github/README.md](../../service-github/README.md).
