# Search Posts with the API

In this tutorial, you search ByteCode.News's posts through its public API. You see how search matches more than the word you typed, read the passage that says why, and then make the search exact. It takes about ten minutes.

You need `curl` and `jq`. You don't need an account.

The results change as posts are added, so yours will differ from the ones shown here.

## 1. Search for a Word

Search for `emergent`, and list the titles of the results:

```bash
curl -s 'https://api.bytecode.news/posts/search?q=emergent' | jq '.totalCount, [.posts[].title]'
```

```text
6
[
  "Make Truth Cheaper than Reputation",
  "Updates for ByteCode.News",
  "Identifying the Actors: What a Byline Actually Certifies",
  …
]
```

Six posts were found. Most of them don't contain the word `emergent`.

## 2. See Why Each Post Matched

Show each result's passage, and the words marked in it:

```bash
curl -s 'https://api.bytecode.news/posts/search?q=emergent' \
  | jq '.posts[] | .match as $m | {title, passage: $m.text, marked: [$m.marks[] | $m.text[.start:.end]]}'
```

```text
{
  "title": "Make Truth Cheaper than Reputation",
  "passage": "… A reputation worth having should emerge from judgment, honesty, competence …",
  "marked": ["emerge"]
}
{
  "title": "Updates for ByteCode.News",
  "passage": "… the idea is that streampack becomes an emergent ontology …",
  "marked": ["emergent"]
}
```

Search reduces every word to its stem. `emergent` and `emerge` share the stem `emerg`, so a post that says "emerge" matches a search for "emergent". The marked word is the one in the post.

## 3. Search for the Exact Word

Put the word in quotes. In a URL, a quote is `%22`:

```bash
curl -s 'https://api.bytecode.news/posts/search?q=%22emergent%22' | jq '.totalCount, [.posts[].title]'
```

```text
1
[
  "Updates for ByteCode.News"
]
```

Now only the post that contains `emergent` is found.

## 4. Mix Exact and Stemmed Words

Quoted and unquoted words work together. Search for posts with a word like `runtime` and the exact word `emerging`:

```bash
curl -s 'https://api.bytecode.news/posts/search?q=runtime%20%22emerging%22' | jq '[.posts[].title]'
```

`runtime` is stemmed, and also finds `runtimes`. `"emerging"` must appear as typed.

## What You've Learned

- An unquoted word finds the word and every word with the same stem.
- Each result's `match` shows a passage, with the words that matched marked.
- A quoted word or phrase must appear exactly.

## Next

- [Search Posts](../how-to/blog/search-posts.md): queries for common tasks.
- [Post Search Syntax](../reference/post-search-syntax.md): the full grammar.
- [Post Search](../explanation/post-search.md): why search works this way.
