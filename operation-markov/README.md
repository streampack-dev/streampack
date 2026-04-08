# operation-markov

`operation-markov` generates short text in the style of a known sender.

## Operations

| Operation | Command / payload | Purpose |
|-----------|-------------------|---------|
| `BeOperation` | `be <username>` or `MarkovRequest` | Builds a Markov chain from recent message history for the named sender and generates a sentence. |

## Behavior

The operation reads up to 1000 recent messages for the sender within the current protocol family, then delegates generation to `MarkovChainService`.

The operation is addressed and uses operation group `markov`.
