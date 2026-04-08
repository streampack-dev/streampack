# operation-sentiment

`operation-sentiment` provides AI-backed sentiment analysis for recent conversation.

## Operations

| Operation | Command / payload | Purpose |
|-----------|-------------------|---------|
| `SentimentOperation` | `sentiment <target>` or `SentimentRequest` | Analyzes recent message logs for a channel/user target and returns a compact sentiment summary. |

## Behavior

The operation is active only when `streampack.ai.enabled=true` and requires `ADMIN`. It reads recent messages from `MessageLogService`, formats them as a transcript, and sends them to `AiService`.

If the requested target differs from the source channel, the result is routed to the requester by direct-message provenance.

The operation is addressed and uses operation group `sentiment`.

## Example Flows

- Analyze the current channel:
  `sentiment #java`
- Analyze another provenance URI as an admin:
  `sentiment irc://libera/%23java`
- Expect cross-channel results to come back privately rather than to the target channel
