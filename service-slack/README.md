# service-slack

`service-slack` is the Slack protocol adapter for Streampack.

It owns:

- workspace registration and connection lifecycle
- channel registration and join state
- outbound reply delivery
- Slack-specific admin commands

## Operations

| Operation | Command | Purpose |
|-----------|---------|---------|
| `SlackAdminOperation` | `slack ...` | Creates, updates, connects, joins, mutes, and inspects Slack workspaces and channels; requires `SUPER_ADMIN`. |

## Admin Command Surface

| Command | Purpose |
|---------|---------|
| `slack connect <name> [<bot-token> <app-token>]` | Register a workspace and connect to it, or reconnect an existing one. |
| `slack disconnect <name>` | Disconnect a configured workspace without deleting it. |
| `slack remove <name>` | Soft-delete a workspace and its channels. |
| `slack autoconnect <name> <true\|false>` | Enable or disable reconnect-at-startup behavior. |
| `slack join <workspace> <#channel>` | Register and join a channel. |
| `slack leave <workspace> <#channel>` | Leave a channel at runtime. |
| `slack autojoin <workspace> <#channel> <true\|false>` | Control whether a channel is joined automatically after connect. |
| `slack mute <workspace> <#channel>` / `slack unmute <workspace> <#channel>` | Mute or unmute channel output. |
| `slack automute <workspace> <#channel> <true\|false>` | Persist automatic muting for a channel. |
| `slack visible <workspace> <#channel> <true\|false>` | Control whether the channel is visible to higher-level features. |
| `slack logged <workspace> <#channel> <true\|false>` | Control whether messages from the channel are logged. |
| `slack signal <name> [character]` | Override or reset the workspace signal character. |
| `slack status [workspace]` | Show runtime/configured status for one workspace or all workspaces. |

## Example Flows

- Register and connect a workspace:
  `slack connect jvm-news xoxb-... xapp-...`
- Join a channel:
  `slack join jvm-news #java`
- Make the join persistent:
  `slack autojoin jvm-news #java true`
- Change the signal character:
  `slack signal jvm-news ~`
- Inspect runtime status:
  `slack status jvm-news`

## Notes

- `slack connect <name>` with no tokens reconnects an existing workspace definition.
- `slack signal <name>` with no character resets the workspace to the default signal.
- All `slack ...` commands require `SUPER_ADMIN`.
