# operation-tell

`operation-tell` sends a message to another target through the normal egress path.

## Operations

| Operation | Command / payload | Purpose |
|-----------|-------------------|---------|
| `TellOperation` | `tell <target> <message>` or `TellRequest` | Delivers attributed text to a target provenance. |

## Target Resolution

Targets are resolved relative to the source provenance:

- `tell alice hello` sends to `alice` on the same protocol and service.
- `tell #java hello` sends to `#java` on the same protocol and service.
- `tell irc://libera/%23java hello` uses the explicit URI.

The operation uses operation group `tell`.

## Example Flows

- Send a private note on the current protocol/service:
  `tell alice build is green`
- Send to a channel on the current protocol/service:
  `tell #java release is live`
- Send to an explicit provenance:
  `tell irc://libera/%23java deploy is complete`
