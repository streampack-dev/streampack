# operation-karma

`operation-karma` provides reputation tracking for arbitrary subjects.

## Operations

| Operation | Command / payload | Purpose |
|-----------|-------------------|---------|
| `SetKarmaOperation` | `<subject>++`, `<subject>--` | Adjusts karma from ambient channel text. |
| `GetKarmaOperation` | `karma <subject>` or `KarmaQueryRequest` | Reports a subject's current karma. |
| `KarmaRankingOperation` | `top karma [N]`, `bottom karma [N]` | Lists top or bottom karma subjects. |

## Behavior

`SetKarmaOperation` is unaddressed so it can react to normal conversation. It protects configured immune subjects, prevents positive self-karma, and reads per-provenance config such as `ignoreEmdash` from `OperationConfigService`.

The module uses operation group `karma`.

## Example Flows

- Increase a subject's karma in ambient chat:
  `kotlin++`
- Decrease a subject's karma:
  `xml--`
- Query a specific subject:
  `karma kotlin`
- Show leaderboard:
  `top karma 5`
