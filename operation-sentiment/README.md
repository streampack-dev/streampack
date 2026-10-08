# operation-sentiment

`operation-sentiment` provides AI-backed sentiment analysis for recent conversation.

## Operations

| Operation | Command / payload | Purpose |
|-----------|-------------------|---------|
| `SentimentOperation` | `sentiment [target]` or `SentimentRequest` | Analyzes recent message logs for the current channel, or a channel target, and returns a compact sentiment summary. |

## Behavior

The operation is active only when `streampack.ai.enabled=true` and requires `ADMIN`. It reads recent messages from `MessageLogService`, formats them as a transcript, and sends them to `AiService`.

If the requested target differs from the source channel, the result is routed to the requester by direct-message provenance.

The answer names the channel as people know it, not by its provenance URI: an IRC channel as written (`#java`), and Discord, Slack and Mattermost channels by name (through the `ChannelNameProvider` beans for Slack and Mattermost). Asked in the channel analyzed, the answer is just the model's line (`Sentiment +6/10 | …`); a cross-channel answer by DM starts `Sentiment for #other:`. A target with no known name is named by its URI.

The operation is addressed and uses operation group `sentiment`.

## Example Flows

- Analyze the current channel:
  `sentiment`
- Analyze another channel on the same network:
  `sentiment #java`
- Analyze another provenance URI as an admin:
  `sentiment irc://libera/%23java`
- Expect cross-channel results to come back privately rather than to the target channel
