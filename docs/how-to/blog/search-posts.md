# Search Posts

Use these queries in the site's search box, or as `q` in `GET /posts/search`. Each result has a passage, with the words that matched marked. The full grammar is in [Post Search Syntax](../../reference/post-search-syntax.md).

## Find a Word and Its Forms

Type the word:

```text
migrate
```

This finds `migrate`, `migrated`, `migrating` and `migration`.

## Find One Exact Word

Put the word in quotes:

```text
"emergent"
```

This finds `emergent`, and not `emerge` or `emerging`.

## Find a Phrase

Put the words in quotes:

```text
"virtual threads"
```

This finds the two words together, in this order. Without the quotes, a post needs both words, but they can be anywhere in it.

## Leave Out a Word

Put `-` before the word:

```text
java -spring
```

This finds posts about Java that don't mention Spring. Put `-` before a quoted phrase to leave out the phrase: `java -"spring boot"`.

## Find Either of Two Words

Put `or` between them:

```text
kotlin or scala
```

To find either of two exact words, quote both:

```text
"emergent" or "emerging"
```

If one side has no quotes, nothing is matched exactly. See the `or` rule in [Post Search Syntax](../../reference/post-search-syntax.md#rules).

## Find Out Why a Post Matched

Read the result's passage. The marked words are the ones that matched. If the marked word isn't the one you typed, it shares a stem with it: `emerging` for `emergent`. To match only the word you typed, quote it.

In the API, the passage is the result's `match`: see [Search](../../reference/blog-http-api.md#search).
