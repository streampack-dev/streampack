# service-irc

`service-irc` is the IRC protocol adapter for Streampack. It owns IRC network/channel configuration, runtime connections, inbound message dispatch, outbound reply delivery, and IRC-specific admin commands.

## Operations

| Operation | Command | Purpose |
|-----------|---------|---------|
| `IrcAdminOperation` | `irc ...` | Creates, updates, connects, joins, mutes, and inspects IRC networks/channels; requires `SUPER_ADMIN`. |

## Admin Command Surface

| Command | Purpose |
|---------|---------|
| `irc connect <name> [<host> <nick> [saslAccount] [saslPassword]]` | Register a network and connect to it, or reconnect an existing one. |
| `irc disconnect <name>` | Disconnect a configured network without deleting it. |
| `irc remove <name>` | Soft-delete a network and its channels. |
| `irc autoconnect <name> <true|false>` | Enable or disable reconnect-at-startup behavior. |
| `irc join <network> <#channel>` | Register and join a channel. |
| `irc leave <network> <#channel>` | Leave a channel at runtime. |
| `irc autojoin <network> <#channel> <true|false>` | Control whether a channel is joined automatically after connect. |
| `irc mute <network> <#channel>` / `irc unmute <network> <#channel>` | Mute or unmute channel output. |
| `irc automute <network> <#channel> <true|false>` | Persist automatic muting for a channel. |
| `irc visible <network> <#channel> <true|false>` | Control whether the channel is visible to higher-level features. |
| `irc logged <network> <#channel> <true|false>` | Control whether messages from the channel are logged. |
| `irc allow-ops <network> <#channel> <true|false>` | Control whether the bot keeps operator status. |
| `irc signal <name> [character]` | Override or reset the network signal character. |
| `irc status [network]` | Show runtime/configured status for one network or all networks. |

## Runtime Components

| Component | Purpose |
|-----------|---------|
| `IrcService` | Entity CRUD and configuration API for IRC networks and channels. |
| `IrcConnectionManager` | Creates and manages one runtime `IrcAdapter` per active network. |
| `IrcAdapter` | Kitteh IRC client listener that resolves users, dispatches ingress, and sends replies. |
| `IrcEgressSubscriber` | Delivers operation results back to IRC. |
| `IrcIdentityProvider` | Describes IRC identity binding syntax to the core linking operations. |
| `IrcSecretRefStartupGuard` | Fails startup if referenced IRC secrets cannot be resolved. |

## Example Flows

- Register and connect to Libera:
  `irc connect libera irc.libera.chat nevet`
- Join the main channel:
  `irc join libera #java`
- Make the join persistent:
  `irc autojoin libera #java true`
- Allow the bot to keep ops in one channel:
  `irc allow-ops libera #java true`
- Inspect runtime status:
  `irc status libera`

## Notes

Inbound IRC messages resolve user identity through `UserResolutionService` before command dispatch. That resolution path must work without an open repository session because IRC events are handled asynchronously on virtual threads.
