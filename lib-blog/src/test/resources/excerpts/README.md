# Excerpt Fixtures

This directory drives `MarkdownExcerptFixtureTests`.

## Files

- `*.md`: source content fixture (markdown or plain text extracted from rendered HTML)
- `*.expected.txt` (optional): exact expected summary text

## Behavior

- If `name.expected.txt` exists for `name.md`, the test asserts an exact summary match.
- If no expected file exists, the test asserts summary quality shape:
  - non-blank output
  - at most 3 sentences

## Live corpus

`live-*.md` files are pulled from `https://api.bytecode.news` post content and are intended for tuning iterations.
