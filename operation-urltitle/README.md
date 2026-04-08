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

## Example Flows

- Paste a URL in chat:
  the operation will emit a title if the host is not ignored and the title is not redundant
- View ignored hosts:
  `url ignore list`
- Suppress a noisy host:
  `url ignore add example.com`
