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

## Generative Prompts

`SuggestArticleOperation` now loads its system prompt through `lib-generative`.

Resolution order for the `suggest` prompt is:

1. `${STREAMPACK_GENERATIVE_PROMPT_DIR}/suggest-prompt.clj`
2. `${STREAMPACK_GENERATIVE_PROMPT_DIR}/suggest-prompt.txt`
3. bundled fallback:
   [`src/main/resources/dev/streampack/ideas/prompts/suggest-prompt.txt`](/Users/joeo/work/streampack-dev/streampack/operation-ideas/src/main/resources/dev/streampack/ideas/prompts/suggest-prompt.txt)

Current prompt context keys:

- `:sourceTitle`
- `:extractedText`

### Default Behavior As Text

The bundled fallback prompt is the starting point for customization. A plain text override can begin by copying that file unchanged and then editing tone or emphasis.

### Default Behavior As Clojure

If you want a dynamic prompt file that reproduces today’s default behavior before adding your own changes, start here:

```clojure
(fn [ctx]
  (str
    "You draft a technical blog summary from extracted source text.\n"
    "Return ONLY valid JSON with this exact schema:\n"
    "{\"title\":\"string\",\"summary\":\"string\",\"tags\":[\"tag1\",\"tag2\"]}\n\n"
    "Rules:\n"
    "- Keep strong signal-to-noise.\n"
    "- Preserve key technical details and tradeoffs.\n"
    "- Prefer classic essay-style prose when the source has enough depth (often ~3-5 paragraphs), but do not pad.\n"
    "- Use fewer paragraphs when source material is thin.\n"
    "- Do not include headings or bullet lists in summary.\n"
    "- Do not speculate beyond available evidence.\n"
    "- You may add brief contextual commentary only when it is well-established and clearly attributed.\n"
    "- tags must be lowercase, no leading '#', no underscores.\n"
    "- return 3-5 tags.\n"
    "- no markdown fences."))
```

That version does not vary by context; it simply mirrors the bundled default in `.clj` form.

### Example Customized Clojure Prompt

Once the default behavior is working, you can start composing prompt fragments:

```clojure
(fn [ctx]
  (letfn [(base []
            "You draft a technical blog summary from extracted source text.")
          (editorial-voice []
            "Avoid flat recap prose. Write with strong editorial signal and clear judgment.")
          (implementation-focus []
            "Preserve technical details, tradeoffs, and practical consequences.")
          (psychology-lens []
            "When it clarifies the point, connect the story to psychology, incentives, or how people reason.")
          (history-blend []
            "When useful, construct examples by blending the topic with computing history or earlier systems.")
          (json-contract []
            "Return ONLY valid JSON with title, summary, and tags.")]
    (str
      (base) "\n"
      (editorial-voice) "\n"
      (implementation-focus) "\n"
      (psychology-lens) "\n"
      (history-blend) "\n"
      "Source title: " (:sourceTitle ctx) "\n"
      "Extracted text:\n" (:extractedText ctx) "\n"
      (json-contract))))
```

That is the preferred customization style for BCN: compose reusable prompt fragments rather than replacing the whole prompt with one static string.

### Current Scope

At the moment, prompt externalization is implemented for `suggest`.

`includeai` in `ArticleOperation` still uses its existing built-in prompt path and is not yet routed through `lib-generative`.

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
