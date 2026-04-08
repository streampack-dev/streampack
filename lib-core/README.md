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
