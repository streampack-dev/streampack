# Tag Names

Posts and factoids share one vocabulary of tags. This page is the reference for the shape a tag
name takes, which tags are system tags, and who sees what. The rules live in
`TagNames` (`lib-taxonomy`); see streampack#140.

## The shape of a tag

A tag being written (a post created or edited, a factoid's `tags` set, a tag the AI or the
heuristic suggests) is normalized:

1. Trimmed and lowercased.
2. One leading `#` is dropped; any other `#` is kept.
3. `-` and `_` become spaces: multi-word tags use spaces.
4. Runs of whitespace become one space, and the ends are trimmed again.
5. If nothing is left, there's no tag.

| Written | Stored |
|---------|--------|
| `#c` | `c` |
| `c#`, `C#`, `#C#` | `c#` |
| `load-testing` | `load testing` |
| `Spring_Boot` | `spring boot` |
| `  spring   boot ` | `spring boot` |
| `c++` | `c++` |
| `日本語` | `日本語` |
| `#`, `-`, `_`, empty | (no tag) |

Symbols other than `#`, `-` and `_` are kept, and so are letters outside Latin. A tag that is
already in this shape (`spring boot`, `java`, `c#`, `ai agents`, `jvm`) is stored unchanged.

There is no length rule in the shape itself. Callers keep their own: the heuristic tag suggester
won't suggest a tag shorter than two characters, and the AI suggesters cap how many they offer.

## System tags

A tag that starts with `_` is a **system tag**, used by the site's own workflow. The one in use is
`_idea`, which marks the drafts that IRC idea capture (`article`, `suggest`) creates and that
`ideas` lists.

- A system tag is only trimmed and lowercased: `_IDEA` is `_idea`, and its `_` is not turned into
  a space. `_` alone is no tag.
- Only the site adds system tags. The AI and heuristic suggesters never offer one.
- `#_idea` is not a system tag: the `#` is dropped, then the `_`, giving `idea`.

### Who sees system tags

| Response | System tags shown |
|----------|-------------------|
| A post that isn't published yet (a draft, or one scheduled for later), to an admin: `GET /posts/{id}`, the post by slug, `GET /admin/posts/pending`, approval to a future date | Yes |
| The same post to its author or anyone else | No |
| A published post, to anyone, admins included | No |
| Post listings, search, `GET /posts?tag=`, SSR pages, MCP `get_post` | No |
| `GET /taxonomy`, the Atlas, MCP factoid `tags` | No |

`GET /posts?tag=_idea` lists nothing. A published post that was an idea keeps `_idea` in storage;
it just isn't shown. Editing a published post through a front end saves the tags the front end was
shown, so that edit drops its system tags; nothing needs them once a post is published.

## Tags already stored

Normalization applies to tags as they are written. Tags stored before it existed are left as they
are, and are read back trimmed and lowercased (`TagNames.stored`), which is also how the factoid
tag SQL (`FactoidAttributeRepository`) reads them: that SQL can't call `TagNames`. So the
taxonomy, the Atlas and factoid tag search keep matching stored tags. A factoid's tag list takes
the normalized shape the next time its `tags` are set; the whole list is re-joined, normalized and
de-duplicated.

## Tag slugs

Each tag has a slug, unique across tags. Tags are found by name, never routed by slug, so the slug
only has to be unique. A new tag's slug is its name slugified; when that is taken, `-2`, `-3` and
so on are added, and a name with nothing Latin in it starts from `tag`. So `c`, `c#` and `c++` are
`c`, `c-2` and `c-3`, and `日本語` is `tag`.
