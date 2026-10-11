# operation-specs

`operation-specs` provides specification lookup for standards and JVM ecosystem identifiers, and
vulnerability lookup by CVE id or package.

## Operations

| Operation | Command / payload | Purpose |
|-----------|-------------------|---------|
| `SpecsOperation` | `rfc <n>`, `rfc<n>`, `jep <n>`, `jep<n>`, `jsr <n>`, `jsr<n>` (or `jcp`), `pep <n>`, `pep<n>` or `SpecRequest` | Looks up specification titles and URLs. |
| `CveOperation` | `cve <term> [version]` or `CveRequest` | Looks up a CVE by id, or the CVEs known in a package (#194). |

## Behavior

The operation runs at priority `95`, after factoid lookup. On a successful lookup it seeds factoids for the selector and selector URL so future lookups can be served from the factoid store.

The operation is addressed and uses operation group `specs`.

Titles are read from each source's page: the RFC Editor's info page for RFCs (older RFCs' own pages
have no title), openjdk.org for JEPs, jcp.org's `<h1>` for JSRs, and peps.python.org for PEPs.
Replies link the spec's own page.

## CVE Lookup

`cve <term> [version]` resolves the term in this order:

1. **A CVE id**, `CVE-2021-44228` or `2021-44228`, from CVE.org's record API. The reply has the
   severity and score when the record has them (the CNA's, else an ADP's such as CISA's), the
   description cut to fit, the publication date and the cve.org link. A rejected or reserved id
   says so; an id CVE.org doesn't know says that.
2. **Package coordinates**, looked up in OSV.dev. Maven is the default, `group:artifact` (a third
   part, `group:artifact:version`, is the version). Other ecosystems take a prefix: `npm:`, `go:`
   (or `golang:`), `crates:` (or `cargo:`), `pypi:` (or `pip:`), `gem:`, `nuget:`, `composer:`,
   `hex:` and `pub:`.
3. **A bare name**: first the alias list (below); otherwise Maven Central is searched for an
   artifact with exactly that artifactId. One match is used; several are listed, most versions
   first, with a request for coordinates. It never guesses.

With a version, a package's reply lists the CVEs affecting it, worst first, and the lowest
version that fixes all of them. Without one, it's a summary: how many, the worst, the newest, and
the lowest version fixing all. An alias naming several packages gets one line for each. A package
OSV has nothing for is "none known to OSV", never "safe". Lists are cut with a count and OSV's
page for the package, so a reply fits a chat line.

OSV often lists one vulnerability more than once (GitHub's advisory and the ecosystem's own, each
an alias of the other); they count once, named by their CVE when there is one, else the GHSA or
OSV id. A severity is the advisory's own rating, or worked out from its CVSS 3 vector.

"Lowest version fixing all" is the lowest version a range says is fixed, above the one asked
about, that no listed vulnerability's ranges take in. Maven versions are ordered as Maven's
`ComparableVersion` orders them, npm, Go, Cargo, Hex and Pub versions as semantic versions. For
PyPI, RubyGems, NuGet and Packagist only plain dotted numbers are compared; when a version there
is anything else, each CVE's fixed versions are listed instead.

Every upstream call has a timeout (`streampack.specs.cve.request-timeout`, 10 seconds). One that
fails, is slow or is rate-limited gets "Couldn't look that up right now" and one WARN log line.

**Caching.** Answers are kept in the module: CVE records and Maven Central searches for a day,
package results for four hours, since that's where new CVEs appear. A failed call isn't kept.

**Factoids.** An id lookup seeds one light factoid, `cve-2021-44228.url`, the cve.org link and
nothing more, since scores and descriptions get revised. Package results never become factoids.

**Aliases.** The shipped list maps `log4j` to `log4j:log4j` and
`org.apache.logging.log4j:log4j-core`, and has `log4j2`, `logback`, `jackson-databind`,
`commons-text`, `commons-collections`, `snakeyaml`, `xstream`, `struts2`, `h2` and `guava`.
`spring` and `netty` aren't there: each is many artifacts. `streampack.specs.cve.aliases.<name>`
adds a name or replaces a shipped one, with a comma-separated list of coordinates; an empty value
drops a shipped one.

| Property | Default | Meaning |
|----------|---------|---------|
| `streampack.specs.cve.aliases.<name>` | the shipped list | Coordinates a name stands for. |
| `streampack.specs.cve.record-ttl` | `P1D` | How long a CVE record is kept. |
| `streampack.specs.cve.search-ttl` | `P1D` | How long a Maven Central search is kept. |
| `streampack.specs.cve.package-ttl` | `PT4H` | How long a package's vulnerabilities are kept. |
| `streampack.specs.cve.connect-timeout` | `5s` | Connect timeout for every upstream. |
| `streampack.specs.cve.request-timeout` | `10s` | Timeout for a whole upstream call. |
| `streampack.specs.cve.cve-url`, `osv-url`, `maven-search-url` | the public services | Upstream addresses (tests point them at a stub). |

## Example Flows

- Look up an RFC:
  `rfc 2616`
- Use compact form:
  `jep456`
- Repeat later:
  seeded factoids can satisfy later requests without another external fetch
- Look up a CVE:
  `cve 2021-44228`
- Ask whether a version is affected:
  `cve org.apache.logging.log4j:log4j-core 2.14.1`, `cve npm:lodash 4.17.15`
- Ask about a name:
  `cve log4j` (an alias: a line per package)
