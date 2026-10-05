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

A factoid's answer is one line: its text, URLs, tags, languages, type and see-also, in that order. When that runs past `streampack.factoid.line-length` (300 by default), whole parts are left out rather than the line cut off: type, languages, tags and see-also, in that order, until it fits, and URLs only if it would still pass 400 characters, where IRC cuts a line. The text is always said. The full factoid is still there (`<selector>.info`, the web pages). `line()` gives the fitted line and what was left out, for anything that needs to know how a factoid will be said (#131).

`FindFactoidCatalogRequest` answers every factoid's selector and text, for `FactoidMatcher` (lib-factoid), which finds the factoids prose mentions (#130).

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
