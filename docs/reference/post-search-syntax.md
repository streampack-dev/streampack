# Post Search Syntax

The query language of `GET /posts/search` (`q`), and of every search box built on it. For the response, see [Search](blog-http-api.md#search). For why it works this way, see [Post Search](../explanation/post-search.md).

## Terms

| Form | Example | Matches a post with |
|------|---------|---------------------|
| A word | `emergent` | the word, or a word with the same stem (`emerge`, `emerged`, `emerging`) |
| Several words | `virtual threads` | every word, each stemmed, anywhere in the post |
| A quoted word | `"emergent"` | the word exactly |
| A quoted phrase | `"kotlin coroutines"` | the words exactly, together and in this order |
| `-` before a word | `-spring` | no word with that stem |
| `-` before a quote | `-"spring boot"` | not that phrase, its words stemmed |
| `or` between terms | `kotlin or scala` | either side |

Terms combine. `java "virtual threads" -spring` needs a word stemmed from `java`, the exact phrase `virtual threads`, and no word stemmed from `spring`.

## Rules

- **Case.** Case is ignored everywhere: `"Emergent"` and `"emergent"` are the same.
- **Stems.** Words are reduced to English stems.
- **Common words.** Stemming ignores common words (`the`, `and`, `of`), so a query of only common words finds nothing, quoted or not. Inside a quoted phrase with other words, they still have to appear: `"state of the art"` matches only that phrase.
- **Exact matches.** A quoted word or phrase must appear as typed, at word edges: `"emergent"` doesn't match `emergently` or `nonemergent`. Space between the words of a phrase can be any run of spaces or line breaks.
- **Fields.** A phrase must sit within one field: the title, the excerpt, the tags, or the body.
- **Unclosed quotes.** A quote that isn't closed runs to the end of the query: `java "virtual threads` is `java "virtual threads"`.
- **Empty quotes.** `""` is ignored.
- **`or` and quotes.** `or` splits the query into alternatives. A post must match one alternative, with that alternative's quoted words exact. If any alternative has no quoted words, the quotes in the query only group words, and nothing is matched exactly. For example, `"emergent" or emerging` matches any post with a word stemmed from either.
- **`or` inside quotes.** Inside quotes, `or` is a word of the phrase: `"this or that"`.
- **What's searched.** The title, the visible tags, the excerpt, and the body as a reader sees it: no markup, no link addresses, entities decoded.
- **Ranking.** Results are ranked by relevance. A match in the title counts most, then tags and the excerpt, then the body. Quotes don't change the ranking.
- **Blank queries.** A blank query returns no results.
