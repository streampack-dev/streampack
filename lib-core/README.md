# lib-core

`lib-core` is the Streampack platform core. It defines the operation pipeline, provenance model, user identity model, service binding model, operation configuration, and shared operation base classes.

## Core Operation Infrastructure

| Type | Purpose |
|------|---------|
| `Operation` | Raw operation contract for message handling. |
| `TypedOperation<T>` | Base class for operations that handle one typed payload. |
| `TranslatingOperation<T>` | Base class for operations that accept text commands and typed payloads. |
| `OperationService` | Executes registered operations in priority order and publishes terminal results. |
| `OperationConfigService` | Stores global and per-provenance operation configuration. |

## Command Parser

`lib-core` also contains the shared text-command parser under `dev.streampack.core.parser`.

Core parser types:

| Type | Purpose |
|------|---------|
| `CommandLexer` | Tokenizes raw text, strips an optional leading `!`, and preserves quoted tokens. |
| `CommandPattern` | Declares fixed literals plus typed positional arguments. |
| `CommandArgType` | Validates and converts one token into a typed value. |
| `CommandPatternMatcher` | Matches input against patterns and can render grammar/help lines from those patterns. |

The parser is intended to become the preferred foundation for text-command parsing, but not every
operation has migrated to it yet.

## Operation Outcomes

- `OperationResult.Success`: definitive answer that is published to egress.
- `OperationResult.Error`: definitive failure that is published to egress.
- `OperationResult.NotHandled`: no operation handled the message.
- `Declined`: recognized but deliberately passed; the chain continues.
- `Consumed`: handled internally; the chain stops and nothing is published to egress.
- `FanOut`: internal dispatch signal that emits child messages and returns a summary success.

## Operations

| Operation | Command / payload | Purpose |
|-----------|-------------------|---------|
| `VersionOperation` | `version` | Reports application/build/git identity. |
| `CreateUserOperation` | `create user <username> <email> <displayName> [role]` or `CreateUserRequest` | Creates users; requires `SUPER_ADMIN`. |
| `AlterUserOperation` | `alter user <username> <field> <value>` or `AlterUserRequest` | Edits username, email, display name, or role; requires admin privileges. |
| `EditProfileOperation` | `EditProfileRequest` | Lets the authenticated user update their own profile fields. |
| `LinkHelpOperation` | `link help [protocol]` | Lists protocol identity binding syntax; requires `SUPER_ADMIN`. |
| `LinkProtocolOperation` | `link user <username> <protocol> <serviceId> <externalIdentifier>` or `LinkProtocolRequest` | Links a protocol identity to a user; requires `SUPER_ADMIN`. |
| `UnlinkProtocolOperation` | `unlink user <username> <protocol> <serviceId> <externalIdentifier>` or `UnlinkProtocolRequest` | Removes a protocol identity binding; requires `SUPER_ADMIN`. |
| `ServiceAdminOperation` | `service ...` | Enables/disables service groups; mutations require `SUPER_ADMIN` and take effect after restart. |
| `OperationAdminOperation` | `operation ...` | Shows and mutates global operation group configuration; mutations require `ADMIN`. |
| `ChannelConfigOperation` | `channel ...` | Shows and mutates per-provenance operation group configuration; mutations require `ADMIN`. |

## Notes

Protocol-specific identity resolution goes through `ServiceBindingRepository` and `UserResolutionService`. Service binding lookups fetch the bound `User` eagerly because protocol adapters resolve users outside repository transaction scopes.

Use `Consumed` for maintenance work such as queueing, buffering, bookkeeping, or deferred
background processing that should not generate user-visible output. Use `Declined` only when
another operation should still get a chance to handle the same message.

For parser details and the new grammar/help rendering surface, see
[docs/reference/command-parser.md](/Users/joeo/work/streampack-dev/streampack/docs/reference/command-parser.md).

## Example Flows

- Bootstrap an operator account:
  `create user alice alice@example.com Alice admin`
- Link that operator to IRC:
  `link user alice irc libera alice@example.com`
- Inspect available identity providers first:
  `link help` or `link help irc`
- Disable a noisy operation globally:
  `operation disable urltitle`
- Disable a group only in one channel:
  `channel disable urltitle for irc://libera/%23java`
- Re-enable a service after maintenance:
  `service enable irc`
