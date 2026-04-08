# operation-dictionary

`operation-dictionary` provides dictionary lookups backed by the Free Dictionary API.

## Operations

| Operation | Command / payload | Purpose |
|-----------|-------------------|---------|
| `DictionaryOperation` | `define <word>` or `DictionaryRequest` | Looks up a definition and returns a short formatted response. |

## Behavior

The operation runs at priority `95`, after normal factoid lookup. This lets known definitions be served from factoids first. On a successful external lookup, it seeds a factoid through `EventGateway` so future lookups can hit the cache.

The operation is addressed and uses operation group `dictionary`.
