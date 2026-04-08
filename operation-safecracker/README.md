# operation-safecracker

`operation-safecracker` provides a timed code-breaking game.

## Operations

| Operation | Command | Purpose |
|-----------|---------|---------|
| `SafecrackerOperation` | `safecracker`, `safecracker <d0> <d1> <d2> <d3>`, `safecracker concede` | Starts, advances, and ends a 4-digit combination guessing game. |

## Behavior

Game state is stored per provenance URI through `ProvenanceStateService`. `SafecrackerTimerService` tracks active timers and cleans up timed-out games.

The operation is addressed and uses operation group `safecracker`.

## Example Flows

- Start a game:
  `safecracker`
- Make a guess:
  `safecracker 3 0 3 0`
- Give up and reveal the code:
  `safecracker concede`
