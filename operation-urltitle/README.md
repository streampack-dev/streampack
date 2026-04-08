# operation-urltitle

`operation-urltitle` fetches page titles for URLs seen in conversation.

## Operations

| Operation | Command | Purpose |
|-----------|---------|---------|
| `UrlTitleOperation` | Ambient text containing URLs | Fetches HTML titles and emits non-redundant titles. |
| `ManageIgnoredHostsOperation` | `url ignore list`, `url ignore add <host>`, `url ignore delete <host>` | Manages the ignored-host list. |

## Behavior

`UrlTitleOperation` is unaddressed and only runs for protocols enabled in `UrlTitleProperties`. It suppresses results for ignored hosts and for titles that are too similar to the URL.

The module uses operation group `urltitle`.
