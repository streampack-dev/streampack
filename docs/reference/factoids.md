# Factoids

This page lists every factoid command, attribute and text form, and the settings that change them.
The examples show commands without the bot's prefix, and the tests run each one. To learn
factoids step by step, see [Teach the Infobot a Factoid](../tutorials/teach-the-infobot-a-factoid.md).

## Commands

| Command | What it does |
|---|---|
| `<selector>` | Says the factoid. |
| `<selector> <words>` | Says the factoid, with `<words>` in place of `$1`. |
| `<selector>=<text>` or `<selector> is <text>` | Sets the text. |
| `<selector>.<attribute>=<value>` or `<selector>.<attribute> is <value>` | Sets an attribute. |
| `<selector>.<attribute>` | Says one attribute. |
| `factoid set <selector>.<attribute> <value>` | Sets an attribute. Use it when `=` or `is` would be read wrongly. |
| `factoid unset <selector>.<attribute>` | Removes one attribute. |
| `forget <selector>` or `<selector>.forget` | Deletes the factoid. |
| `forget <selector>.<attribute>` | Deletes one attribute. |
| `<selector>.info` | Lists the attributes, and when and by whom the factoid last changed. |
| `<selector>.literal` | Shows the text as it was set. |
| `<selector>.stats` | Says how often the factoid was read, and when last. |
| `<selector>.lock`, `<selector>.unlock` | Locks or unlocks the factoid. Admins only. |
| `search <words>` | Lists the factoids that match the words. A list longer than 200 characters is cut to the first 10. |
| `tag <tag>` | Lists the factoids with the tag, cut to 10 as `search` is. A tag alias finds its tag's factoids. |

`factoid set` and `factoid unset` need the word `factoid`. A bare `set` or `unset` is not a factoid
command.

## Selectors

A selector can have several words. Case does not matter when you read a factoid: `Kotlin` and
`kotlin` are the same factoid.

<!-- factoid-example: multi-word-selector -->
```text
<alice> spring boot=An opinionated framework
<bot> ok, alice: updated spring boot.
<alice> spring boot
<bot> spring boot is An opinionated framework.
```

With `is`, the value can hold `=`. With `=`, the value can hold the word `is`.

<!-- factoid-example: is-value-with-equals -->
```text
<alice> expression is 2+2=4
<bot> ok, alice: updated expression.
<alice> expression
<bot> expression is 2+2=4.
```

<!-- factoid-example: equals-value-with-is -->
```text
<alice> motto=this is the way
<bot> ok, alice: updated motto.
<alice> motto
<bot> motto is this is the way.
```

A message that names no factoid gets no answer from the factoids. Another command, such as the
dictionary, can still answer it.

<!-- factoid-example: not-a-factoid -->
```text
<alice> just a random message that matches nothing
<bot> (no reply)
```

## Attributes

| Attribute | Other name | Value | In the answer |
|---|---|---|---|
| `text` | | The fact. See [Text Forms](#text-forms). | Yes, first. |
| `urls` | `url` | Web addresses, separated by commas. | Yes. |
| `tags` | `tag` | Tags, separated by commas. See [Tag Names](tags.md). | Yes. |
| `languages` | `language` | Languages, separated by commas. | Yes. |
| `type` | | A kind, such as "library". | Yes. |
| `seealso` | | Related selectors, separated by commas. | Yes, last. |
| `see` | | One selector. Makes this factoid an alias. | No. |
| `maven` | | Maven coordinates, `group:artifact`. | No. |

The answer says the parts in the order of this table. A text that ends in punctuation gets no
extra period.

<!-- factoid-example: punctuation-kept -->
```text
<alice> question=Is this a test?
<bot> ok, alice: updated question.
<alice> question
<bot> question is Is this a test?
```

`maven` makes a link to mvnrepository.com. It is not in the factoid's answer.

<!-- factoid-example: maven -->
```text
<alice> spring=A Java framework
<bot> ok, alice: updated spring.
<alice> spring.maven=org.springframework:spring-core
<bot> ok, alice: updated spring.
<alice> spring.maven
<bot> Maven: org.springframework:spring-core https://mvnrepository.com/artifact/org.springframework/spring-core
<alice> spring
<bot> spring is A Java framework.
```

`info` lists the attributes. It also gives the time of the last change, which the example leaves
out.

<!-- factoid-example: info -->
```text
<alice> spring=A Java framework
<bot> ok, alice: updated spring.
<alice> spring.url=https://spring.io
<bot> ok, alice: updated spring.
<alice> spring.info
<bot> The factoid for spring has the following attributes: text and urls, and was last modified at …
```

## Text Forms

| Form | Effect |
|---|---|
| `<reply>` at the start | The bot says the text without "selector is" in front. |
| `$1` | Replaced by the words after the selector. |
| `(a\|b\|c)` | The bot picks one choice each time. Groups can nest. An empty choice says nothing. |
| `~selector` inside a choice | Says another factoid's text, or nothing if it does not exist. |

Parentheses without `|` are plain text. See [Write Factoid Text](../how-to/infobot/write-factoid-text.md).

### Arguments

A factoid with `$1` needs words after its selector. Without them, the bot answers
"`<selector>`: Not enough arguments to replace placeholder."

A factoid without `$1` takes no words after its selector. The message then is not a factoid
lookup, so the factoid gives no answer.

<!-- factoid-example: argument-not-wanted -->
```text
<alice> simple=Just a value
<bot> ok, alice: updated simple.
<alice> simple extra words
<bot> (no reply)
```

## Aliases

A factoid with `see` says the factoid that `see` names. The rules:

- An alias can point at another alias. A chain is at most `streampack.max-hops` factoids long, the first alias included (default 3). A longer chain gives no answer.

<!-- factoid-example: see-chain -->
```text
<alice> a=placeholder
<bot> ok, alice: updated a.
<alice> b=placeholder
<bot> ok, alice: updated b.
<alice> c=final value
<bot> ok, alice: updated c.
<alice> a.see=b
<bot> ok, alice: updated a.
<alice> b.see=c
<bot> ok, alice: updated b.
<alice> a
<bot> c is final value.
```

- An alias to a factoid that does not exist gives no answer.

<!-- factoid-example: see-missing -->
```text
<alice> foo=placeholder
<bot> ok, alice: updated foo.
<alice> foo.see=nonexistent
<bot> ok, alice: updated foo.
<alice> foo
<bot> (no reply)
```

- Aliases that point at each other give no answer.

<!-- factoid-example: see-cycle -->
```text
<alice> foo=placeholder
<bot> ok, alice: updated foo.
<alice> bar=placeholder
<bot> ok, alice: updated bar.
<alice> foo.see=bar
<bot> ok, alice: updated foo.
<alice> bar.see=foo
<bot> ok, alice: updated bar.
<alice> foo
<bot> (no reply)
```

- Asking for one attribute of an alias reads the alias itself, not the factoid it points at.

<!-- factoid-example: see-own-attribute -->
```text
<alice> foo=own text
<bot> ok, alice: updated foo.
<alice> foo.see=bar
<bot> ok, alice: updated foo.
<alice> bar=bar text
<bot> ok, alice: updated bar.
<alice> foo
<bot> bar is bar text.
<alice> foo.text
<bot> foo is own text.
```

## Locking

Only an admin can lock or unlock a factoid. A locked factoid refuses `=`, `is`, `factoid set`,
`factoid unset` and every form of `forget`, from everyone.

<!-- factoid-example: lock-needs-admin -->
```text
<alice> locktest=value
<bot> ok, alice: updated locktest.
<alice> locktest.lock
<bot> Lock/unlock requires admin privileges.
```

## Errors

The bot says an error as text. The console puts `ERROR: ` in front of it.

<!-- factoid-example: forget-missing -->
```text
<alice> forget nonexistent
<bot> Factoid 'nonexistent' not found.
```

## Reading Counts

Each read of a factoid counts as an access: the answer, one attribute, `info` and `literal`.
`stats`, `forget`, `lock` and `unlock` do not count, and neither does setting a factoid. `stats`
gives the count and the time of the last access, which the example leaves out.

<!-- factoid-example: stats -->
```text
<alice> popular=interesting factoid
<bot> ok, alice: updated popular.
<alice> popular.stats
<bot> popular has never been accessed.
<alice> popular
<bot> popular is interesting factoid.
<alice> popular
<bot> popular is interesting factoid.
<alice> popular.stats
<bot> popular has been accessed 2 times, last accessed at …
```

## Settings

| Property | Default | Effect |
|---|---|---|
| `streampack.factoid.line-length` | `300` | The longest answer, in characters, before parts are left out. See [The One-Line Answer](../explanation/factoids.md#the-one-line-answer). |
| `streampack.max-hops` | `3` | The longest alias chain, in factoids. It also limits other chains of operations. |
| `streampack.factoid.draft.examples` | `spring boot, 4gl, maven, openapi` | The factoids shown to the AI when it drafts a factoid. |

## Factoids in Posts

`[[selector]]` in a post links to the factoid. `[[text|selector]]` shows `text` as the link. See
[Link Factoids from Posts](../how-to/blog/link-factoids-from-posts.md).

## HTTP API

Front ends read and change factoids over HTTP. For the request and response bodies, see
[OpenAPI](openapi.md).

| Endpoint | What it does |
|---|---|
| `GET /factoids` | Lists factoids, a page at a time. `?q=` searches; `?since=` lists only recent changes. |
| `GET /factoids/{selector}` | One factoid, with its attributes. |
| `PUT /factoids/{selector}` | Sets a factoid. Signed-in users only, when web changes are on. |
| `DELETE /factoids/{selector}` | Forgets a factoid, or one attribute with `?attribute=`. Signed-in users only, when web changes are on. |
| `PUT /factoids/{selector}/lock`, `DELETE /factoids/{selector}/lock` | Locks or unlocks. Admins only. |
| `POST /factoids/derive` | Drafts a factoid with AI. Nothing is stored. |
| `GET /admin/factoids/report` | Where the factoids' links could be better. Admins only. |

## Related

- [Manage Factoids](../how-to/infobot/manage-factoids.md)
- [How Factoids Work](../explanation/factoids.md)
- [operation-factoid](../../operation-factoid/README.md), for developers
