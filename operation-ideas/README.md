# operation-ideas

`operation-ideas` turns conversation into draft blog posts. An idea is captured in chat (a title, some
text, an excerpt of the channel, optionally an AI summary) or seeded from a URL, and saved as a
**draft** post tagged `_idea`. Nothing is published: an editor finds the draft in the blog's pending
queue, edits it, and publishes it or throws it away.

All commands are addressed: prefix them with the bot's trigger (`!article ...` on IRC) or its name.

## Operations

| Operation | Commands | Who | Purpose |
|-----------|----------|-----|---------|
| `ArticleOperation` | `article <title>`, then `content <text>`, `logs <duration>`, `includeai`, `noai`, `done`, `cancel` | anyone | A session that builds an idea a piece at a time and saves it as a draft. |
| `SuggestArticleOperation` | `suggest <http(s)://url>` | `ADMIN` | Fetches a page, drafts a summary and tags from it (with AI, if available), and saves a draft. |
| `IdeasBrowseOperation` | `ideas`, `ideas search <term>`, `ideas remove #N` | `ADMIN` | Lists, searches, and removes the drafts tagged `_idea`, by private message. |

## Capturing an idea: `article`

```
<dreamreal> !article Java taught vs Java practiced
<bot> Idea session started: "Java taught vs Java practiced". ...
<dreamreal> !content Courses teach the language; jobs use the ecosystem.
<dreamreal> !logs 30m
<dreamreal> !includeai
<dreamreal> !done
<bot> Idea saved as draft: "Java taught vs Java practiced" (2 content blocks). AI summary appended for admin review.
```

| Command | What it does |
|---------|--------------|
| `article <title>` | Starts a session. The title may be quoted (`article "A title"`). One session per person per channel; start another with the first still open and you're told to finish it first. |
| `content <text>` | Adds `<text>` as a paragraph (a *content block*) of the body. Repeat for more paragraphs. |
| `logs <duration>` | Adds the channel's recent conversation as a content block: `10m`, `30m`, `1h`. See [Channel excerpts](#channel-excerpts). |
| `includeai` / `noai` | Turns the AI summary on or off for `done` (off by default). See [AI summary](#ai-summary). |
| `done` | Saves the draft and ends the session. |
| `cancel` | Ends the session and discards it. |

Only `article` is recognized outside a session; the others are recognized only while you have one
open in that channel, so `!done` or `!logs` mean nothing to this module otherwise.

### The session

- A session belongs to one person in one channel (the channel's provenance plus the sender's nick),
  so two people can capture ideas in the same channel at once.
- It is saved through `ProvenanceStateService`, and every command restarts its inactivity clock.
- After `streampack.ideas.session-timeout-minutes` (default 5) with no command, the session is saved
  as a draft anyway, and the channel is told so. A session saved this way gets **no AI summary**,
  even with `includeai` on.

### Channel excerpts

`logs <duration>` reads the channel's message log (`MessageLogService`):

- The duration is capped at `streampack.ideas.max-log-duration` minutes (default 60): `logs 6h` takes
  the last hour. The reply says what was taken: `Added 42 log messages (last 1h) as content block #2.`
- At most `streampack.ideas.max-log-messages` messages (default 100) are taken: the **latest** ones
  in that window.
- Only messages the channel logs are there: a channel with logging off has nothing to add.

The excerpt goes into the body as a fenced block, one line per message, as a chat client shows it:

````markdown
```text
<NeXeN> go look. i don't have intellij installed
* pebble joined #java
<dreamreal> NeXeN: you're an !idea person!?!? What do you use?
```
````

Actions (`* nick waves`) and channel events (joins, parts, topic changes, which are logged without a
sender of their own) appear as they are. The fence is longer than any run of backticks in the
excerpt, so a message containing a fence can't end it.

### AI summary

With `includeai`, `done` asks the configured `AiService` for a summary of the title and every content
block (logs included), with the site's existing tags offered as candidates. The result is appended to
the body as a section headed **AI Draft Summary (Generated)**, with its suggested tags listed as
text, for the editor to keep, rewrite, or delete; the suggested tags are not applied to the post. If
no AI is configured, or the answer can't be used, the draft is saved without it and the reply says
why.

This summary uses a prompt built into `ArticleOperation`; unlike `suggest`, it can't be overridden
yet (see [Generative Prompts](#generative-prompts)).

### Who the draft belongs to

If the sender is signed in, or their chat identity is bound to a site account (a `ServiceBinding`
for that protocol and service), the draft is that account's. Otherwise it's saved without an author,
and the body ends with an attribution line:

```markdown
---
*Contributed by NeXeN via irc://libera/%23java*
```

`ideas` reads that line to say who contributed each idea.

## Seeding an idea from a page: `suggest`

`suggest <url>` (admins only) fetches the page, extracts its text, and saves a draft whose body is a
summary, the source URL, any warnings from the fetch, and a note that it was generated for review.
With an `AiService` available, the title, summary and 3 to 5 tags (applied to the post, beside
`_idea`) come from the AI, using the prompt
described below; without one, the page's title and the start of its text serve. An invalid TLS
certificate stops the fetch with a warning.

## Reviewing ideas: `ideas`

Admins only. The list is sent by private message, oldest first, numbered:

- `ideas`: every draft tagged `_idea`.
- `ideas search <term>`: those whose title contains `<term>`.
- `ideas remove #N`: deletes idea number `N` as `ideas` numbers them now (a soft delete), so list
  first: a removal renumbers the rest.

## Configuration

| Property | Default | Meaning |
|----------|---------|---------|
| `streampack.ideas.session-timeout-minutes` | `5` | Inactivity before an open session is saved as it stands. |
| `streampack.ideas.max-log-duration` | `60` | The longest span, in minutes, `logs` will take. |
| `streampack.ideas.max-log-messages` | `100` | The most messages `logs` will take. |
| `STREAMPACK_GENERATIVE_PROMPT_DIR` | | Where `suggest`'s prompt override is looked for. |

## Generative Prompts

`SuggestArticleOperation` now loads its system prompt through `lib-generative`.

Resolution order for the `suggest` prompt is:

1. `${STREAMPACK_GENERATIVE_PROMPT_DIR}/suggest-prompt.clj`
2. `${STREAMPACK_GENERATIVE_PROMPT_DIR}/suggest-prompt.txt`
3. bundled fallback:
   [`src/main/resources/dev/streampack/ideas/prompts/suggest-prompt.txt`](src/main/resources/dev/streampack/ideas/prompts/suggest-prompt.txt)

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

At the moment, prompt externalization is implemented for `suggest` only. `includeai` in
`ArticleOperation` still uses its built-in prompt and is not yet routed through `lib-generative`.
