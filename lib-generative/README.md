# lib-generative

`lib-generative` is the shared runtime library for prompt resolution in Streampack.

Its job is to let generative features keep a bundled prompt in source control while still allowing deployments to override that prompt from the filesystem at runtime. It also supports dynamic prompt generation through Clojure files.

This is infrastructure, not a feature module. Consumer modules such as `operation-ideas` use it to resolve prompts before handing them to AI services.

## What It Provides

| Type | Purpose |
|------|---------|
| `GenerativeProperties` | Spring configuration properties for external prompt lookup. |
| `GenerativeAutoConfiguration` | Registers the shared prompt service. |
| `GenerativePromptService` | Resolves a prompt by name from filesystem overrides or classpath fallback. |

## Core Contract

The main entry point is:

```kotlin
promptService.render(
    promptName = "suggest-prompt",
    fallbackClasspathResource = "dev/streampack/ideas/prompts/suggest-prompt.txt",
    context = mapOf("sourceTitle" to title, "extractedText" to extractedText),
)
```

This returns the final prompt text to pass into the consumer’s AI path.

## Configuration

`lib-generative` is configured with:

```text
streampack.generative.prompt-dir
```

In environment-variable form:

```text
STREAMPACK_GENERATIVE_PROMPT_DIR
```

Example:

```text
STREAMPACK_GENERATIVE_PROMPT_DIR=/opt/streampack/generative
```

If the directory is blank or not set, only the bundled classpath fallback is used.

## Resolution Order

For a prompt named `suggest-prompt`, resolution is:

1. `${promptDir}/suggest-prompt.clj`
2. `${promptDir}/suggest-prompt.txt`
3. classpath fallback supplied by the caller

That order is deliberate:

- `.clj` is the highest-precedence dynamic override
- `.txt` is the simple static override
- classpath fallback keeps the feature working with no deployment customization

## File Formats

### Plain Text

`suggest-prompt.txt` is read as-is and returned verbatim.

Example:

```text
You draft technical article suggestions for Bytecode News.
Prefer concise summaries with strong editorial signal.
Return only valid JSON with title, summary, and tags.
```

Use this when you want a deployment-specific prompt without any code-like logic.

### Clojure

`suggest-prompt.clj` must evaluate to a single function. That function is called with a context map and must return the final prompt text.

Example:

```clojure
(fn [ctx]
  (str
    "You draft technical blog summaries from extracted source text.\n"
    "Source title: " (:sourceTitle ctx) "\n"
    "Extracted text:\n" (:extractedText ctx) "\n"
    "Return only valid JSON with title, summary, and tags."))
```

This is evaluated on every call to `render(...)`, so changing the file changes runtime behavior without rebuilding or restarting the app.

## Context Semantics

The `context` argument passed from Kotlin is converted into a Clojure map with keyword keys.

Example Kotlin:

```kotlin
mapOf(
    "sourceTitle" to "Signals and Noise",
    "extractedText" to "Long extracted article text...",
)
```

becomes Clojure-accessible as:

```clojure
(:sourceTitle ctx)
(:extractedText ctx)
```

Null values are omitted when converting the Kotlin map to the Clojure map.

## Failure Behavior

External prompt failures are intentionally non-fatal.

If:

- a `.clj` file cannot be parsed or evaluated
- a `.txt` file cannot be read
- the external directory is missing

then `GenerativePromptService` logs a warning and falls back to the next resolution step.

This means:

- bad deployment overrides do not take the whole feature down
- the bundled prompt remains the safety net

## Design Guidance

### Prefer Prompt Composition

The intended use is prompt composition, not only simple static replacement.

A Clojure prompt can assemble fragments for:

- editorial voice
- implementation focus
- psychology or reasoning references
- historical framing
- audience-specific tone

Example:

```clojure
(fn [ctx]
  (letfn [(base []
            "You draft technical blog summaries from extracted source text.")
          (editorial-voice []
            "Avoid flat recap prose. Write with strong editorial signal and clear judgment.")
          (implementation-focus []
            "Emphasize tradeoffs, consequences, and implementation detail.")
          (psychology-lens []
            "When useful, connect the story to psychology, incentives, or how people reason.")
          (history-blend []
            "When useful, construct examples by blending the topic with computing history.")
          (json-contract []
            "Return only valid JSON with title, summary, and tags.")]
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

### Start From The Bundled Prompt

For consumers that already have a default prompt, the easiest migration path is:

1. copy the bundled text prompt
2. confirm that the override reproduces current behavior
3. convert it to `.clj` only when you need runtime composition

This keeps prompt tuning incremental and low-risk.

## Security And Operational Notes

- `.clj` prompt files are executable code in the sense that they are evaluated by the JVM’s embedded Clojure runtime.
- Only mount prompt directories from trusted sources.
- If you want lower risk and less dynamism, use `.txt` overrides instead of `.clj`.
- Because `.clj` is evaluated per call, expensive prompt logic should be avoided.
- This module is designed for prompt construction, not for arbitrary business logic.

## Current Consumer

Current first consumer:

- `operation-ideas` `SuggestArticleOperation`

It uses:

- bundled fallback: [`operation-ideas/src/main/resources/dev/streampack/ideas/prompts/suggest-prompt.txt`](/Users/joeo/work/streampack-dev/streampack/operation-ideas/src/main/resources/dev/streampack/ideas/prompts/suggest-prompt.txt)
- runtime prompt name: `suggest-prompt`

Current context keys provided by that consumer:

- `:sourceTitle`
- `:extractedText`

Other modules can adopt `lib-generative` by supplying their own prompt name, fallback resource, and context map.

## Adding A New Consumer

Typical pattern:

1. Add `lib-generative` as a dependency.
2. Put the default prompt in your module resources.
3. Inject `GenerativePromptService`.
4. Call `render(promptName, fallbackClasspathResource, context)`.
5. Pass the returned text into your AI service.

Example:

```kotlin
val systemPrompt =
    promptService.render(
        "my-feature-prompt",
        "dev/streampack/myfeature/prompts/my-feature-prompt.txt",
        mapOf(
            "title" to title,
            "body" to body,
        ),
    )
```

## Testing Strategy

The module’s own tests cover:

- bundled classpath fallback
- filesystem `.txt` override
- filesystem `.clj` evaluation with context

Consumer modules should also have their own focused tests proving that:

- the expected fallback resource is used
- external overrides are actually consumed by the feature

## Related Docs

- [Configure Generative Prompts](/Users/joeo/work/streampack-dev/streampack/docs/how-to/deploy/configure-generative-prompts.md)
- [operation-ideas README](/Users/joeo/work/streampack-dev/streampack/operation-ideas/README.md)
