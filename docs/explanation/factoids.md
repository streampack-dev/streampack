# How Factoids Work

A factoid is a short fact the infobot keeps under a name, its *selector*. This page explains why
the bot answers as it does. For the commands, see [Factoids](../reference/factoids.md).

## The Lookup Comes Late

Every message goes through the bot's operations in order of priority. The factoid lookup has
priority 90, so most commands see a message before the lookup does. When a command takes a
message, the lookup never sees it.

When no factoid matches, the lookup does not answer. Operations after it can then answer, such as
the dictionary and the specification lookups. They often store what they find as a factoid, so the
next request is faster.

A message that matches nothing gets no answer at all, so ordinary conversation in a channel
does not make the bot speak.

## Aliases and See-Also

Both attributes name other factoids, but they do different things:

- `see` makes an alias. The alias says the other factoid, as if you had asked for that one. Use it
  for a second name: `jdk` and `java development kit`.
- `seealso` adds related names to the factoid's own answer. Use it to point from a narrow topic to
  a broader one: from `karaf` to `osgi`.

An alias keeps its own attributes. A request for one attribute, such as `alias.text`, reads the
alias itself. So `forget alias.see` turns the alias back into a factoid with its own text.

A chain of aliases stops after `streampack.max-hops` factoids, so two aliases that point at each
other can't loop forever.

## The One-Line Answer

The bot says a factoid on one line: the text, then the URLs, tags, languages, type and see-also.
Chat services cut long lines, and IRC cuts a line near 400 characters.

When the line is longer than `streampack.factoid.line-length` (default 300), the bot leaves out
whole parts. It drops the type first, then the languages, the tags and the see-also. It drops the
URLs only if the line would still pass 400 characters. The text always stays. The bot never cuts a
part in the middle.

<!-- factoid-example: too-long-drops-tags -->
```text
<alice> longish=a framework a framework a framework a framework a framework a framework a framework a framework a framework a framework a framework a framework a framework a framework a framework a framework a framework a framework a framework a framework a framework a framework
<bot> ok, alice: updated longish.
<alice> longish.url=https://longish.example
<bot> ok, alice: updated longish.
<alice> longish.tags=one,two,three,four,five
<bot> ok, alice: updated longish.
<alice> longish
<bot> longish is a framework a framework a framework a framework a framework a framework a framework a framework a framework a framework a framework a framework a framework a framework a framework a framework a framework a framework a framework a framework a framework a framework. URL: https://longish.example
```

The full factoid is still there: `info` lists its attributes, and the web pages show all of it.

## Tags Have One Vocabulary

Factoid tags and post tags use the same names. When you set `tags`, each tag is normalized: lower
case, with `-` and `_` as spaces. A tag alias is stored as its tag. A new tag that looks doubtful is
kept, and an admin reviews it later. The person who set the tags only sees "ok". See
[Tag Names](../reference/tags.md).

## The Knowledge Base Is a Graph

Factoids that name each other with `seealso` form a graph. An admin report,
`GET /admin/factoids/report`, shows where the graph could be better: a parent that lists a child
without a link back, text that names a factoid without a link, see-also names that are not
factoids, and answers too long for one line. The report only advises. It never changes a factoid.

## Related

- [Teach the Infobot a Factoid](../tutorials/teach-the-infobot-a-factoid.md)
- [operation-factoid](../../operation-factoid/README.md), for developers
