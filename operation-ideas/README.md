# operation-ideas

`operation-ideas` captures and manages article ideas from chat and source URLs.

## Operations

| Operation | Command | Purpose |
|-----------|---------|---------|
| `ArticleOperation` | `article <title>`, `content <text>`, `logs <duration>`, `includeai`, `noai`, `done`, `cancel` | Runs a stateful article-idea capture session and saves the result as draft content. |
| `SuggestArticleOperation` | `suggest <http(s)://url>` | Fetches source content, optionally uses AI to draft a summary/tags, and saves a draft idea; requires `ADMIN`. |
| `IdeasBrowseOperation` | `ideas`, `ideas search <term>`, `ideas remove #N` | Lists, searches, and removes draft ideas tagged `_idea`; requires `ADMIN`. |

## Behavior

`ArticleOperation` stores per-user session state through `ProvenanceStateService` and uses `MessageLogService` to include recent channel logs when requested. AI enrichment is optional and depends on an available `AiService`.

The module bridges chat-originated ideas into the blog content model. It depends on blog repositories and typed blog content requests.

## Example Flows

- Start an idea session:
  `article Better JVM startup stories`
- Add body text:
  `content We should compare CDS, CRaC, and native-image tradeoffs.`
- Pull recent logs into the draft:
  `logs 30m`
- Enable AI summarization/tags:
  `includeai`
- Finalize the draft:
  `done`
- Seed a draft from a source URL:
  `suggest https://inside.java/2026/04/example`
- Review current idea queue:
  `ideas`
