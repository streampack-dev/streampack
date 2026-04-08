# operation-factoid

`operation-factoid` provides factoid storage, lookup, metadata, search, and taxonomy integration.

## Operations

| Operation | Command / payload | Purpose |
|-----------|-------------------|---------|
| `GetFactoidOperation` | `<selector>`, `<selector>.<attribute>`, typed `FactoidQueryRequest` | Catch-all factoid lookup and attribute rendering. |
| `SetFactoidOperation` | `<selector>=<value>`, `<selector> is <value>`, `<selector>.<attribute>=<value>` | Stores text or attribute values. |
| `SetFactoidVerbOperation` | `factoid set <selector>.<attribute> <value>` | Explicit verb form for setting mutable attributes. |
| `UnsetFactoidVerbOperation` | `factoid unset <selector>.<attribute>` | Removes a mutable attribute. |
| `ForgetFactoidOperation` | `forget <selector>`, `forget <selector>.<attribute>` | Deletes a whole factoid or one attribute. |
| `SearchFactoidOperation` | `search <term>` | Searches factoids by text. |
| `TagSearchOperation` | `tag <term>` | Searches factoids by tag. |
| `FindFactoidLinkMetadataOperation` | `FindFactoidLinkMetadataRequest` | Returns factoid metadata for link rendering. |
| `FindFactoidTagTaxonomyOperation` | `FindFactoidTagTaxonomyRequest` | Provides tag counts to taxonomy aggregation. |

## Behavior

Factoid lookup is intentionally late in the chain, with `GetFactoidOperation` at priority `90`. Cache-miss operations such as specs and dictionary run after it.

The interactive factoid operations are addressed and use operation group `factoid`. Some typed operations exist for cross-module integration rather than human command input.

## Example Flows

- Create a factoid:
  `spring is A Java framework`
- Add metadata:
  `spring.tags=java,framework`
- Read the factoid:
  `spring`
- Read a specific attribute:
  `spring.tags`
- Search for related entries:
  `search framework`
- Remove one attribute:
  `factoid unset spring.tags`
- Forget the selector entirely:
  `forget spring`
