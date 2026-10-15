# Post Search

Post search answers two questions for a reader: which posts are about this, and why each one was found.

## Words Are Stemmed

Search uses Postgres's full-text search with the English dictionary. Every word, in the posts and in the query, is cut to its stem before they're compared: `emergent`, `emerge`, `emerged` and `emerging` all become `emerg`. A search for `emergent` therefore finds a post that says "an emerging runtime".

That's usually what a reader wants: `migrate` should find "migration", `thread` should find "threads". It surprises a reader who then looks for the word itself, with the browser's find, and doesn't see it.

The fields are weighted: the title counts most, then tags and the excerpt, then the body. A post with the word in its title ranks above one with it deep in the body.

## Quoted Words Are Exact

A quoted word or phrase must also appear as typed, ignoring case, at word edges: `"emergent"` finds `emergent` and not `emerging`. The rest of the query is still stemmed, so `threads "emergent"` needs a word like `thread` and the exact word `emergent`.

The quoted part is an extra condition, not a different search. The query still goes to `websearch_to_tsquery`, as typed, and finds candidates through the index. Each candidate's text must then also match a pattern made from the quoted words. Ranking is unchanged.

A phrase must sit within one field. Title, excerpt, tags and body are kept apart, so `"kotlin coroutines"` doesn't match a title ending "Kotlin" followed by an excerpt starting "Coroutines".

## Each Result Says Why It Matched

Each result carries a passage, `match`, with the matching words marked. It comes from the first field with a match: the body, then the excerpt, the title, the tags.

- **A stemmed search** asks Postgres's `ts_headline` for the passage, with the same query as the search. The marks fall on the words as the post wrote them: `emerging`, for a search of `emergent`. The reader sees why the post matched even when the word they typed isn't there.
- **A search with quoted words** marks those words exactly, in a window around their first appearance. Those are the words the result had to contain.

The passage is plain text, with the marks as offsets rather than HTML. A front end builds its own markup from them and escapes the text, so it never has to trust markup from a post.

## The Text That's Searched

The index, the exact match and the passage all read a post's body through one function, `html_text`. It turns the rendered HTML into the text a reader sees:

- Block tags (paragraphs, list items, headings) become spaces.
- Inline tags (links, emphasis, code) disappear without a space, so `<strong>un</strong>expected` reads, and is indexed as, `unexpected`.
- Entities are decoded: `&amp;` is `&`.

Link addresses and markdown syntax aren't searched.

See [Post Search Syntax](../reference/post-search-syntax.md) for the grammar, and [Search](../reference/blog-http-api.md#search) for the response.
