# operation-poetry

`operation-poetry` provides AI-backed poem generation and analysis.

## Operations

| Operation | Command / payload | Purpose |
|-----------|-------------------|---------|
| `PoemOperation` | `poem <topic>` or `PoemRequest` | Generates a short poem and emits it as a loopback result. |
| `PoemAnalysisOperation` | `a poem: <text>` or `PoemAnalysisRequest` | Analyzes poem meter, rhyme scheme, and form. |

## Behavior

Both operations are active only when `streampack.ai.enabled=true`. `PoemOperation` formats generated poems as `a poem: ...` and marks the result for loopback, allowing `PoemAnalysisOperation` to analyze generated content through the normal pipeline.

The operations are addressed and use operation group `poetry`.

## Example Flows

- Generate a poem:
  `poem distributed systems`
- Let the loopback analysis run automatically on the generated result
- Analyze supplied poem text explicitly:
  `a poem: Two roads diverged/...`
