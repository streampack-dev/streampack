# operation-specs

`operation-specs` provides specification lookup for standards and JVM ecosystem identifiers.

## Operations

| Operation | Command / payload | Purpose |
|-----------|-------------------|---------|
| `SpecsOperation` | `rfc <n>`, `rfc<n>`, `jep <n>`, `jep<n>`, `jsr <n>`, `jsr<n>` (or `jcp`), `pep <n>`, `pep<n>` or `SpecRequest` | Looks up specification titles and URLs. |

## Behavior

The operation runs at priority `95`, after factoid lookup. On a successful lookup it seeds factoids for the selector and selector URL so future lookups can be served from the factoid store.

The operation is addressed and uses operation group `specs`.

Titles are read from each source's page: the RFC Editor's info page for RFCs (older RFCs' own pages
have no title), openjdk.org for JEPs, jcp.org's `<h1>` for JSRs, and peps.python.org for PEPs.
Replies link the spec's own page.

## Example Flows

- Look up an RFC:
  `rfc 2616`
- Use compact form:
  `jep456`
- Repeat later:
  seeded factoids can satisfy later requests without another external fetch
