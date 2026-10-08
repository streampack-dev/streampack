# Tag Names

Posts and factoids share one vocabulary of tags. This page is the reference for the shape a tag
name takes, which tags are system tags, who sees what, and the vocabulary itself: aliases, the
stoplist and the review queue. The shape lives in `TagNames` and the vocabulary in `TagVocabulary`
(both `lib-taxonomy`); see streampack#140.

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

## The vocabulary

Every tag written goes through `TagVocabulary`: post create and edit, a factoid's `tags`, and
(without creating anything) the AI and heuristic suggesters. A contribution is never made
interactive: one message gets one reply, and nothing about its tags is asked of the contributor.

A tag being written is normalized, then, in this order:

1. **A system tag** (`_idea`) passes through unchanged. It's never stoplisted, aliased or queued.
2. **A stoplisted term** is dropped, silently.
3. **An alias** is stored as the tag it means, silently: with `k8s` an alias of `kubernetes`,
   writing `K8s` stores `kubernetes`.
4. **An existing tag** is stored as it is.
5. **Anything else is new.** It's accepted as written and created. When it looks doubtful it's
   also put in the review queue for an admin (below); the contributor never hears of it.

The tags the vocabulary knows are the `tags` table's rows and the tags factoids carry. A factoid
tag that's written creates its `tags` row too, so the table holds every tag in use from now on.

### The review queue

A new tag is queued when it looks like one of these. Nothing is ever folded automatically.

| Hint | When | Example |
|------|------|---------|
| `PLURAL` | It and an existing tag differ by one trailing `s`. Both forms must be two characters or more. | `compiler` when `compilers` exists, or the other way round |
| `MISSING_COMMA` | It's several words that split, at spaces, into two or more existing tags (fewest parts first). | `java kotlin` when `java` and `kotlin` exist; `spring boot kotlin` into `spring boot`, `kotlin` |
| `AI` | No rule matched, but the AI near-miss (below) named an existing tag. | `k8s` and `kubernetes` |

The plural rule is deliberately simple: a trailing `s`, nothing else (`classes` isn't matched with
`class`). So `news` *is* hinted against `new`: it's a true trailing-s pair. It's created as `news`,
and the queue entry lets an admin keep it with one action. `news` and `new` are never merged unless
an admin aliases them.

The queue has one entry per tag. An admin can:

- **alias** it to an existing tag: every post and every factoid carrying it is re-pointed to that
  tag, in one transaction; its own row goes; any aliases of it move to the target; and the name
  becomes an alias, so writing or looking it up finds the target from then on;
- **split** it, for a missing comma, into its parts (the hint's, by default): every post and
  factoid carrying it carries the parts instead, in one transaction, and its row goes;
- **keep** it as a real tag;
- **dismiss** it, leaving the tag as it is.

Re-pointing keeps one of each tag: a post or factoid that already carries the target doesn't get
it twice. Factoid tag lists are rewritten in place, the rest of each list left as it was stored,
locked factoids included.

### The AI near-miss

When AI is on (`AI_ENABLED`), each genuinely new tag is sent, with the existing vocabulary, to the
moderation model (`AI_MODERATION_MODEL`, no thinking): is it a near-duplicate of one existing tag,
which, how confident, and why? It runs after the write that created the tag has committed, on a
thread of its own, so the contribution's reply never waits on it and never changes.

Its answer (candidate, confidence 0 to 1, reason, model) is stored on the tag's queue entry, to rank
it (the most confident first) and explain it. A candidate that isn't an existing tag is ignored. An
answer for a tag that no rule queued makes an `AI` entry; an answer with no candidate makes none.

Applying an answer automatically is off by default, since a wrong merge (`java`/`javascript`) is
quiet damage. `streampack.tags.ai-auto-apply-threshold` (`STREAMPACK_TAGS_AI_AUTO_APPLY_THRESHOLD`)
turns it on: an answer at or above the threshold aliases the tag to the candidate at once, recorded
as done by `ai:<model>`. `streampack.tags.ai-near-miss` (`STREAMPACK_TAGS_AI_NEAR_MISS`, default
`true`) turns the near-miss off altogether. Without AI the queue keeps the rule-based hints.

### Aliases and the stoplist

Aliases and stoplisted terms are normalized names. An alias names an existing tag; making one
re-points whatever already carries the alias, as aliasing a queued tag does. Removing an alias
re-points nothing back: the name is just free again. A stoplisted term is dropped from tags written
from then on; tags already stored are left alone. System tags can't be aliased or stoplisted.

Every change (alias, unalias, split, keep, dismiss, stop, unstop) is recorded with who made it and
when (`tag_action`). The admin endpoints are under `/admin/tags` (see the
[Blog HTTP API](blog-http-api.md#admin-tags)), and admins have the same as text commands (see
[Admin Text Operations](admin-text-operations.md#tag-vocabulary)).

### Lookups

Looking a tag up follows an alias: `GET /posts?tag=`, the factoid tag search (`tag <term>`), and
`GET /atlas/places/{tag}` all find the alias's tag's posts, factoids and place. Otherwise the name
is matched as stored tags are read (below), or in its normalized shape when only that is in use.

## Tags already stored

Normalization applies to tags as they are written. Tags stored before it existed are left as they
are, and are read back trimmed and lowercased (`TagNames.stored`), which is also how the factoid
tag SQL (`FactoidAttributeRepository`) reads them: that SQL can't call `TagNames`. So the
taxonomy, the Atlas and factoid tag search keep matching stored tags. A factoid's tag list takes
the normalized shape the next time its `tags` are set; the whole list is re-joined, resolved
through the vocabulary and de-duplicated.

Nothing already stored is migrated here: existing hyphenated tags, `self-hosted`, plural pairs and
factoid tags without a `tags` row are cleaned up by an approved migration (#140's third part),
which leaves an alias behind for each merge.

## Tag slugs

Each tag has a slug, unique across tags. Tags are found by name, never routed by slug, so the slug
only has to be unique. A new tag's slug is its name slugified; when that is taken, `-2`, `-3` and
so on are added, and a name with nothing Latin in it starts from `tag`. So `c`, `c#` and `c++` are
`c`, `c-2` and `c-3`, and `日本語` is `tag`.
