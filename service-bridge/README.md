# service-bridge

`service-bridge` manages directional copy bridges between provenances.

It lets operators mirror text from one channel or destination into another without changing the
underlying protocol adapters.

## Operations

| Operation | Command | Purpose |
|-----------|---------|---------|
| `BridgeAdminOperation` | `bridge ...` | Shows provenance information and manages bridge pairs. `bridge provenance` and `bridge info` are readable by anyone; mutations require `ADMIN`. |
| `BridgeCopyOperation` | Ambient channel text | Copies messages across configured bridge directions. |

## Command Surface

| Command | Purpose |
|---------|---------|
| `bridge provenance` | Show the current channel's provenance URI. |
| `bridge info` | Show whether the current channel is bridged and in which direction. |
| `bridge copy <source-uri> <target-uri>` | Establish a directional copy from source to target; requires `ADMIN`. |
| `bridge remove <source-uri> <target-uri>` | Remove one directional copy; requires `ADMIN`. |
| `bridge list` | List configured bridge pairs; requires `ADMIN`. |

## Example Flows

- Discover the current channel URI before wiring a bridge:
  `bridge provenance`
- Inspect the current channel's bridge state:
  `bridge info`
- Mirror one IRC channel into another:
  `bridge copy irc://libera/%23java irc://oftc/%23jvm`
- Remove that direction later:
  `bridge remove irc://libera/%23java irc://oftc/%23jvm`

## Notes

- Bridges are directional. A reverse direction must be created explicitly if you want a bidirectional mirror.
- `bridge remove` removes only the named direction. If the reverse direction also exists, it stays active.
