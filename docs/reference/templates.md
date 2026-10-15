# Prompt and Message Templates

Text the server sends or uses that you can replace without a rebuild. Each template has a name. A file named after it in `generative/` (`STREAMPACK_GENERATIVE_PROMPT_DIR`) replaces the bundled default. To change one, copy its default below into a file of that name, and edit it. See [Customize Prompts and Messages](../how-to/deploy/configure-generative-prompts.md).

## Files and Placeholders

| Template | What it is | Placeholders |
|----------|-----------|--------------|
| `mail-sign-in-code` | The one-time sign-in code mail. | `{{siteName}}`, `{{code}}`, `{{minutes}}` |
| `mail-verify-email` | The mail that confirms an email address. | `{{siteName}}`, `{{link}}`, `{{hours}}` |
| `mail-new-draft` | The mail to admins when someone submits a post. | `{{siteName}}`, `{{title}}`, `{{author}}`, `{{url}}`, `{{markdown}}` |
| `suggest-prompt` | The AI prompt for `suggest`, which drafts an article from a link. | `{{sourceTitle}}`, `{{extractedText}}` |
| `rss-rating-rubric` | The rubric the AI judges feed items by when it guesses their ratings. | none |

- **Lookup order.** For a template named `mail-sign-in-code`, the server uses the first of:
  1. `mail-sign-in-code.clj`
  2. `mail-sign-in-code.txt`
  3. the bundled default
- **Placeholders.** In a `.txt` file, or the bundled default, `{{key}}` is replaced by the value of `key`. A placeholder the template has no value for stays as written. A value goes in as it is: `{{…}}` inside a value, such as a submitted post's text, isn't replaced.
- **Clojure.** A `.clj` file is a function of one map, keyed by the same names as keywords (`(:siteName ctx)`). It returns the text.
- **Mail templates.** A mail template's first line is `Subject: …`, and the rest is the body. Without that line, the whole template is the body, and the subject is the site's name, or for `mail-new-draft`, "New post submission: *title*".
- **`siteName`** is `BLOG_SITE_NAME`.
- **When a file is read.** Every time the template is used, so an edited file takes effect without a restart.

## Default Text

### `mail-sign-in-code`

Bundled at `lib-core/src/main/resources/dev/streampack/core/mail/mail-sign-in-code.txt`.

````text
Subject: Your sign-in code for {{siteName}}

Your one-time sign-in code is: {{code}}

This code will expire in {{minutes}} minutes.
If you did not request this, please ignore this email.
````

### `mail-verify-email`

Bundled at `lib-core/src/main/resources/dev/streampack/core/mail/mail-verify-email.txt`.

````text
Subject: Verify your email address

Welcome to {{siteName}}!

Please verify your email address by visiting:
{{link}}

This link will expire in {{hours}} hours.
````

### `mail-new-draft`

Bundled at `lib-blog/src/main/resources/dev/streampack/blog/mail/mail-new-draft.txt`.

````text
Subject: New post submission: {{title}}

A new post draft was submitted on {{siteName}}.

Title: {{title}}
Author: {{author}}
URL: {{url}}

{{markdown}}
````

### `suggest-prompt`

Bundled at `operation-ideas/src/main/resources/dev/streampack/ideas/prompts/suggest-prompt.txt`.

````text
You draft a technical blog summary from extracted source text.
Return ONLY valid JSON with this exact schema:
{"title":"string","summary":"string","tags":["tag1","tag2"]}

Rules:
- Keep strong signal-to-noise.
- Preserve key technical details and tradeoffs.
- Prefer classic essay-style prose when the source has enough depth (often ~3-5 paragraphs), but do not pad.
- Use fewer paragraphs when source material is thin.
- Do not include headings or bullet lists in summary.
- Do not speculate beyond available evidence.
- You may add brief contextual commentary only when it is well-established and clearly attributed.
- tags must be lowercase, no leading '#', no underscores.
- return 3-5 tags.
- no markdown fences.
````

### `rss-rating-rubric`

Bundled at `service-rss/src/main/resources/dev/streampack/rss/prompts/rss-rating-rubric.txt`.

````text
You help the editor of Bytecode News (BCN), a site about programming, decide which items from
the news feeds it follows are worth an article. For each item, guess how the editor would rate it.

BCN publishes what contributes something worth thinking about. That can be new: a new idea,
technique or library. It can be old information presented in a new way, or old information people
have forgotten (TDD isn't new, and it's still worth a good article). Anything that stands out.

BCN is centred on the JVM (Java, Kotlin, Scala, Clojure, the JVM itself), but it isn't limited to
it. Go, Rust, JavaScript, 6502, Z80, Mac, Windows, Linux: anything a JVM programmer might also care
about counts. Topic alone never makes an item worth writing about, and being off the JVM never makes
one dull.

BCN is honest and positive. It doesn't hype, and it doesn't shame: content that's malicious,
misleading or poorly done is ignored, never singled out as a bad example. Rate such items DULL.

RATES: worth writing about; it contributes something worth thinking about.
MIGHT: could be worth writing about with the right angle, but nothing about it stands out.
DULL: routine or not worth BCN's attention. Release notes and version bumps with nothing to say,
marketing and hype, a rehash that adds nothing, news with nothing to add to it, and anything
malicious or poorly done.

Most items are DULL or MIGHT; RATES is rare.
````
