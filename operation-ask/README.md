# operation-ask

`operation-ask` provides a general-purpose AI question operation.

## Operations

| Operation | Command / payload | Purpose |
|-----------|-------------------|---------|
| `AskOperation` | `ask <question>` or `AskRequest` | Sends a concise question to the configured AI service with recent channel context. |

## Behavior

`AskOperation` is active only when `streampack.ai.enabled=true`. It uses `MessageLogService` to collect recent conversation from the current provenance and includes that context in the AI prompt when available.

The operation is addressed, uses operation group `ask`, and is throttled to 5 requests per hour per provenance URI.

## Example Flows

- Ask for a concise technical answer:
  `ask why does virtual-thread pinning matter here?`
- Ask with recent channel context in scope:
  `ask what were we saying about GraalVM a minute ago?`
