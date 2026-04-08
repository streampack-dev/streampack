# operation-hangman

`operation-hangman` provides a per-channel Hangman game.

## Operations

| Operation | Command | Purpose |
|-----------|---------|---------|
| `HangmanOperation` | `hangman`, `hangman <letter>`, `hangman solve <word>`, `hangman concede` | Starts, displays, and advances a Hangman game for the current provenance. |
| `HangmanAdminOperation` | `hangman block <word>`, `hangman unblock <word>` | Maintains the blocked-word list; requires `ADMIN`. |

## Behavior

Game state is stored through `ProvenanceStateService`, so each channel or DM context has an independent game. Word selection and blocklist management are delegated to `HangmanService`.

The play operation uses operation group `hangman`; the admin operation uses `hangman-admin`.
