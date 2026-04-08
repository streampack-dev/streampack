# operation-21

`operation-21` provides the `21 Matches` channel game.

## Operations

| Operation | Command | Purpose |
|-----------|---------|---------|
| `MatchesOperation` | `21`, `21 matches`, `21 take <1-3>`, `21 concede` | Runs a per-provenance game where players and the bot remove 1-3 matches. The player forced to take the last match loses. |

## Behavior

Game state is stored through `ProvenanceStateService`, keyed by the current provenance URI. Each channel or DM context therefore has an independent game.

The operation is addressed and uses operation group `21-matches`.

## Example Flows

- Start a game in a channel:
  `21`
- Take two matches:
  `21 take 2`
- Concede the current game:
  `21 concede`
