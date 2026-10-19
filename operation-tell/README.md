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
- `tell "foo bar" hello` and `tell "mattermost://work/foo bar" hello`: a quoted target can contain
  spaces. In a full address, `%20` is a space too. An unclosed quote is refused.

A protocol module can read the target before it is sent, with a `TellTargetResolver` (lib-core).
Mattermost's resolver (`MattermostTellTargets`) turns `@name` into the person's direct-message
channel with the bot, and a channel's name into its id. When the resolver refuses a target, the
sender gets the reason, and nothing is sent. A protocol with no resolver gets the target as typed.

The operation uses operation group `tell`.

## Example Flows

- Send a private note on the current protocol/service:
  `tell alice build is green`
- Send to a channel on the current protocol/service:
  `tell #java release is live`
- Send to an explicit provenance:
  `tell irc://libera/%23java deploy is complete`
