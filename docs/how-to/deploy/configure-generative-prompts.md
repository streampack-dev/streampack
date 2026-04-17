# Configure Generative Prompts

Streampack can load AI/generative prompts from a runtime filesystem directory instead of relying only on bundled defaults.

This is useful when:

- you want to tune prompts without rebuilding the image
- you want deployment-specific prompt voice or policy
- you want to experiment with multiple prompt lanes
- you want mounted prompt updates to take effect without restarting the server

## Runtime Configuration

Set:

```text
STREAMPACK_GENERATIVE_PROMPT_DIR=/opt/streampack/generative
```

and mount a host directory there.

For Docker Compose:

```yaml
services:
  server-streampack:
    environment:
      STREAMPACK_GENERATIVE_PROMPT_DIR: /opt/streampack/generative
    volumes:
      - ./generative:/opt/streampack/generative:ro
```

## Prompt Resolution Order

For a prompt named `suggest-prompt`, Streampack resolves:

1. `/opt/streampack/generative/suggest-prompt.clj`
2. `/opt/streampack/generative/suggest-prompt.txt`
3. the bundled classpath fallback

If an external prompt fails to load, Streampack logs the problem and falls back safely.

## Plain Text Override

The simplest override is a text file:

```text
/opt/streampack/generative/suggest-prompt.txt
```

Example:

```text
You draft technical article suggestions for Bytecode News.
Prefer concise, essay-like summaries with strong editorial voice.
Favor implementation detail, tradeoffs, and practical implications.
Return only valid JSON with title, summary, and tags.
```

Use this first if you only need a deployment-specific static prompt.

## Dynamic Clojure Prompt

If `suggest-prompt.clj` exists, it takes precedence over the text file.

The file should evaluate to a single function that accepts a context map and returns the prompt text.

Example:

```clojure
(fn [ctx]
  (letfn [(base []
            "You draft technical blog summaries from extracted source text.")
          (editorial-voice []
            "Avoid flat recap prose. Write with strong editorial signal and clear judgment.")
          (implementation-focus []
            "Emphasize technical tradeoffs, consequences, and implementation detail.")
          (psychology-lens []
            "Where it fits, connect the story to psychology, behavior, or how people reason.")
          (history-blend []
            "When useful, construct examples by blending the topic with computing history or prior systems.")
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

This pattern is useful because the prompt file can build the final prompt from reusable fragments instead of relying on a single hardcoded string.

The function is evaluated each time the prompt is requested, so changing the file changes runtime behavior without rebuilding the container.

## Composing Prompt Fragments

The likely real use is not a simple mode switch. It is prompt composition.

For example, a prompt file can decide to mix in ideas such as:

- stronger editorial voice
- references to psychology or reasoning behavior
- blended examples from computing history
- different balances between summary, critique, and implementation detail

Example:

```clojure
(fn [ctx]
  (let [fragments
        [(when true
           "You are writing for technical readers who are bored by flat recap prose.")
         (when true
           "Prefer observations that reveal tradeoffs, incentives, or failure modes.")
         (when true
           "When it clarifies the point, bring in psychology or decision-making behavior.")
         (when true
           "When useful, construct examples by blending the topic with computing history.")
         (str "Source title: " (:sourceTitle ctx))
         (str "Extracted text:\n" (:extractedText ctx))
         "Return only valid JSON with title, summary, and tags."]]
    (->> fragments
         (remove nil?)
         (clojure.string/join "\n"))))
```

For `operation-ideas` `suggest`, the current context includes:

- `:sourceTitle`
- `:extractedText`

More keys can be added later if the caller needs richer prompt composition.

That gives you a way to tune prompt tone and synthesis behavior over time without changing application code.

## Current Consumer

The first consumer of this mechanism is `operation-ideas` for the `suggest` command.

The bundled fallback prompt lives at:

[`operation-ideas/src/main/resources/dev/streampack/ideas/prompts/suggest-prompt.txt`](/Users/joeo/work/streampack-dev/streampack/operation-ideas/src/main/resources/dev/streampack/ideas/prompts/suggest-prompt.txt)

## Developer Notes

Shared prompt loading lives in `lib-generative`, not in `operation-ideas`.

Use `GenerativePromptService` for new generative features that need:

- bundled fallback prompts
- deployment-specific filesystem overrides
- optional dynamic Clojure evaluation
